# Design Notes

Why the scanner is built the way it is. The README covers what it does and how to run it; this is
the reasoning underneath, including the decisions that could reasonably have gone the other way.

## 1. The thing that makes this hard

Comparing a fund's price to the sum of its constituents takes about ten lines. Every one of those
ten lines is a way to be confidently wrong:

- Price the basket at the mid and a perfectly fair market shows half a spread of edge on every
  fund, all day.
- Ignore size and a one-cent dislocation on a symbol with 100 shares resting reads the same as one
  with 100,000.
- Ignore the flat creation fee and small dislocations all look tradable, when in practice the fee
  is exactly what makes them not.
- Trust the feed and one stale print produces a headline number that evaporates on contact.

So the interesting part of the problem is not finding the dislocation. It is deciding which
dislocations are real, and how much of one there actually is. The structure of the code follows
from that: the comparison is a few lines in `ArbitrageScanner`, and most of the surrounding code
is about size, cost and data quality.

## 2. Modelling decisions

**Quotes are two-sided, and a missing side is absent rather than zero.** `Quote` holds
`Optional<Level>` per side. Using a zero price for an empty side would let it silently participate
in arithmetic; making it absent forces every caller to decide what a one-sided market means for
what it is doing. It turns out to mean something specific and useful: a fund with only an offer
cannot be created, because there is nothing to sell the shares into, but it can still be redeemed.
That falls out of the type rather than needing a special case.

**Every leg crosses the spread.** Buying the basket pays constituent offers; selling it hits
constituent bids. Both directions therefore pay two spreads, and the reported edge is what would
survive actually doing it. This is the single biggest difference between a number worth acting on
and a number that merely looks good.

**Size is integer creation units.** A partial creation unit is not a thing the fund will exchange,
so units are whole and every leg's available size is divided down by integer division. The binding
leg is reported, because in practice that is the actionable part: it names the symbol to work an
order in if the trade is worth more size.

**The fund's own quote can be the binding leg.** Easy to overlook, since attention goes to the
basket, but the fund's resting size caps the trade just as hard as any constituent's.

**Fees are two shapes, not one.** A rate on turnover and a flat charge per unit behave completely
differently: the rate scales with the trade and the flat fee does not, which is why a small
dislocation on a large basket can clear fees while the same dislocation on a small one cannot. The
sample deliberately contains a fund in each category.

**Look-through stops at a quote.** A constituent that is itself a fund is bought or sold directly
when the market quotes it, because that is what a trader would do. Only an unquoted sub-fund is
expanded into its own basket, since assembling it is then the only way to get it. This is also why
the expansion is memoised: on a real universe many funds share the same sub-funds, and expanding
each once per scan rather than once per fund is the difference the benchmark shows at 5,000 funds.

**Look-through requires whole sub-units.** Expanding a holding of 150 shares of a fund whose
creation unit is 100 shares would mean assembling one and a half creation units, which cannot be
done. Rather than rounding or approximating, the fund is skipped with a distinct reason. Reporting
`INDIVISIBLE_LOOKTHROUGH` instead of quietly pricing something unobtainable is the conservative
choice, and it is visible in the output rather than buried.

**Staleness is measured against the feed, not the clock.** The reference time is the newest quote
in the file. A snapshot replayed next month therefore gives the same answer it gave when captured,
which makes the sample reproducible and the tests deterministic. A live feed would pass the wall
clock instead, which is why `Universe` carries the reference time rather than the scanner reading
it from the environment.

**A crossed book is refused, not traded.** Bid at or above ask is arbitrage on its face, and it is
almost always a data fault: two venues stitched together, or a print that arrived out of order.
Trading it is how a scanner reports its largest and least real opportunity.

**Every skip carries a reason.** Nine of them, all in the output. A scanner that prints only the
trades gives no way to tell "nothing was mispriced today" from "the feed was broken and everything
was rejected". The distinction matters more in practice than the trades do.

**Money is `double`.** A deliberate scope choice for two-decimal prices. Comparisons here are
against thresholds rather than for exact equality, so accumulated error changes nothing that
matters at this precision. A system handling arbitrary decimals or booking real positions would
use integer minor units; that is noted as a limitation rather than pretended away.

## 3. Structure

```text
CLI -> readers -> Universe -> ArbitrageScanner -> Opportunity -> writer -> file
```

IO lives at the two ends. Everything between is pure: given a `Universe`, a `QuotePolicy` and a
`FeeSchedule`, the scanner returns the same list of `Opportunity` every time, with no clock, no
filesystem and no logging in the path. That is what lets a test state a book inline and assert an
answer, and it is why the policy and fee schedule are constructor arguments rather than settings
read somewhere inside.

`Opportunity` is a sealed interface over `Trade` and `Skip`, so the writer's switch is exhaustive
and adding an outcome is a compile error at every site that handles one. `Trade` validates in its
compact constructor: an executed trade with zero units or a non-positive gross edge is not a state
the program should be able to represent, so it cannot be constructed.

Parse-time validation is aggressive and fails with a file and line number. The circular-basket
check in particular lives in the reader rather than the scanner, which is what lets `LookThrough`
recurse without a depth guard: by the time the scanner sees a universe, the basket graph is known
to be acyclic.

## 4. Testing

Three layers, each catching what the others cannot.

**Hand-built books** state the arithmetic in a comment and assert the answer that follows from it.
These are the tests that would catch a sign error in the direction logic, and they are readable
enough to serve as documentation of the economics.

**Randomised properties** generate several thousand books per run and assert invariants rather
than values: a fund quoted inside its basket's own spread never trades, a fund quoted away from it
always trades in the correct direction, and the edge never exceeds the dislocation that produced
it. The seed is fixed so a failure reproduces. Writing these turned up two real problems, both in
the generator rather than the scanner, which is its own lesson: a generated book with a sub-cent
spread rounds to a locked or crossed market, and a fund priced at a few cents loses more to the
second decimal place than the dislocation being tested. The scanner was right to reject both.

**A derived sample.** `tools/generate_sample.py` builds the snapshot and works out the expected
report from the prices, in Python. The Java scanner then has to reach the same numbers by itself.

That last one is worth being explicit about, because it is the part most often done badly. The
obvious approach is to run the program, save the output as `expected.csv`, and diff against it
forever. That checks only that behaviour has not changed; if the logic was wrong on the day the
file was captured, the test enshrines the error and will defend it against every future fix. Here
the expected report is derived independently, in another language, from the same inputs. The two
implementations agreeing is evidence about the economics, not just about stability. CI runs the
generator with `--check` so the committed sample cannot drift from the script, then diffs the
built jar's output against it.

Fund prices in the sample are not hand-written either. Each fund is quoted at the fair value of
its own basket, moved by a stated number of basis points. Every dislocation in the sample is
therefore deliberate and declared in one place, and the leaf prices can be reshuffled by changing
the seed without any expected answer needing to be retyped.

## 5. What was left out, on purpose

- **No dependency injection framework.** For one pipeline, constructor wiring in `Main` is clearer
  than any container.
- **No logging in the scanner.** It is pure and silent; all IO and all error reporting happen at
  the CLI boundary.
- **No best-effort parsing.** Malformed input fails immediately with a line number. Guessing at
  what a broken row meant is how bad data reaches the trading logic.
- **No incremental book updates.** The scanner recomputes a whole universe. That is the honest
  shape for a snapshot tool, and the benchmark shows what it costs. Making it incremental is a
  real change with a real design behind it, not a flag, so it is listed as an extension rather
  than half-built.
