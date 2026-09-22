# ETF Basket Arbitrage

[![ci](https://github.com/SSK2822/etf-basket-arbitrage/actions/workflows/ci.yml/badge.svg)](https://github.com/SSK2822/etf-basket-arbitrage/actions/workflows/ci.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

A command line scanner that reads a snapshot of top-of-book quotes and a set of ETF creation
baskets, and reports where a fund is priced far enough away from its own basket to be worth
trading. For each fund it decides the direction, sizes the trade against the quantity actually
resting on the book, charges commission and the fund's creation fee, and reports the edge that
survives.

## The trade

An ETF can be exchanged with the fund for the basket of shares that backs it, in blocks called
creation units. That gives its price an anchor, and two ways to act when it drifts:

- **Create**, when the fund is rich: buy the basket at the offer, deliver it to the fund, and sell
  the shares received at the bid.
- **Redeem**, when the fund is cheap: buy the fund at the offer, redeem it for the basket, and sell
  the basket at the bid.

Both are complete round trips, so the position is flat at the end and the spread between the two
sides is the profit.

## What this scanner does differently

Comparing a fund's price against the sum of its constituents is the easy part, and on its own it
produces a stream of opportunities that cannot be traded. Most of the work here is in not doing
that:

- **Both sides of the book, never the mid.** Every leg is priced at the side a trade would actually
  hit: constituent offers when buying the basket, constituent bids when selling it. Marking to mid
  would report roughly half a spread of edge on a market that is perfectly fair.
- **Sized, not flagged.** An opportunity is only as big as the thinnest leg allows. The scanner
  takes the resting size on every leg, divides by the quantity that leg contributes to a creation
  unit, and reports how many units can be filled and which symbol ran out first.
- **Costed.** Commission on both legs plus the fund's flat per-unit creation fee. The flat fee is
  usually what decides a small dislocation, and a scanner that ignores it will recommend trades
  that lose money.
- **Suspicious of its own inputs.** A stale print, a crossed book or an empty side all look
  exactly like free money. Each is detected and reported as a reason rather than a trade.
- **Look-through valuation.** A constituent that is itself a fund is traded directly when it is
  quoted. When it is not quoted, it is expanded into its own basket, recursively, because
  assembling it is the only way to obtain it.

## Build and test

```console
./gradlew build                 # compile, lint, and test
./gradlew test                  # unit and property tests
./gradlew spotlessApply check   # format, then lint and test
./gradlew scanSample            # scan the bundled sample and diff against the expected report
./gradlew benchmark             # measure scan throughput
./gradlew shadowJar             # build the runnable jar
```

The build targets Java 21 through a Gradle toolchain, so a newer local JDK is fine as long as
Gradle can resolve a Java 21 toolchain.

## Usage

```console
./gradlew run --args="--quotes quotes.csv --baskets baskets.csv --out report.csv"
```

or as a self-contained jar:

```console
java -jar build/libs/etf-arb-all.jar \
  --quotes quotes.csv --baskets baskets.csv --out report.csv
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--quotes` | required | CSV of top-of-book quotes |
| `--baskets` | required | CSV of creation baskets |
| `--out` | required | CSV report to write |
| `--fee-bps` | `0.5` | commission in basis points of traded notional, charged on both legs |
| `--unit-fee` | `500` | flat fee per creation unit |
| `--max-quote-age` | `5` | seconds a quote stays tradable |
| `--min-edge` | `0.01` | smallest net edge worth reporting |

Exit codes: `0` success, `2` usage error, `3` invalid input, `4` IO failure.

## Input

Quotes, one row per symbol. Leaving a price and its size empty means that side of the book is
empty, which is different from a price of zero.

```csv
symbol,bid,bid_size,ask,ask_size,as_of
NVDX,182.40,4000,182.46,3800,2026-09-21T14:30:02Z
KELV,311.05,1500,311.12,1400,2026-09-21T14:30:01Z
PINCH,,,88.40,3000,2026-09-21T14:30:02Z
```

Creation baskets, one row per constituent. `unit_size` is the number of ETF shares exchanged for
one creation unit, and `quantity` is the constituent shares per creation unit. Funds are reported
in the order they first appear.

```csv
etf,unit_size,constituent,quantity
CMPX,100,NVDX,300
CMPX,100,ARTM,400
CMPX,100,KELV,200
```

Columns may appear in any order. Blank lines and `#` comments are ignored. Malformed input fails
with the file, the line number and what was wrong, rather than a best-effort guess.

Quotes are judged for staleness against the newest timestamp in the file rather than the wall
clock, so a captured snapshot gives the same answer whenever it is replayed.

## Output

```csv
etf,action,units,gross_edge,fees,net_edge,limiting_symbol,reason
CMPX,CREATE,7,793.00,514.25,1951.27,KELV,
CLNX,REDEEM,12,802.00,504.53,3569.64,CLNX,
BALX,NONE,0,0.00,0.00,0.00,,EDGE_BELOW_THRESHOLD
```

`gross_edge` and `fees` are per creation unit; `net_edge` is the whole trade, net of fees, across
all `units`. `limiting_symbol` is the leg that capped the size. Amounts use two decimals under a
fixed locale.

When there is no trade, `reason` says why:

| Reason | Meaning |
| --- | --- |
| `NO_EDGE` | the fund and its basket are priced consistently |
| `EDGE_BELOW_THRESHOLD` | there was a dislocation, but fees left too little of it |
| `NO_EXECUTABLE_SIZE` | real edge, but not enough resting size to fill one creation unit |
| `STALE_QUOTE` | a quote was older than `--max-quote-age` |
| `CROSSED_QUOTE` | a bid was at or above its ask, which is a data fault |
| `ONE_SIDED_QUOTE` | neither direction had both of its legs quoted |
| `UNQUOTED_ETF` | the fund itself is not quoted |
| `UNPRICED_CONSTITUENT` | a constituent is neither quoted nor a fund with a known basket |
| `INDIVISIBLE_LOOKTHROUGH` | a holding of an unquoted sub-fund is not a whole number of its units |

## Tests

```console
./gradlew test
```

Three layers:

- **Hand-built books.** Small universes written inline, where the arithmetic is stated in a
  comment and the expected answer follows from it by hand. These cover direction, sizing, fees,
  look-through and each way market data can be bad.
- **Randomised properties.** Six thousand generated books check three invariants: a fund quoted
  inside its basket's own spread never yields a trade, a fund quoted away from it always yields
  one in the correct direction, and the reported edge never exceeds the dislocation that created
  it. The seed is fixed, so a failure is reproducible.
- **A derived sample.** `tools/generate_sample.py` writes the sample snapshot and works out the
  report the scanner should produce, from the prices, in Python. The scanner then has to arrive at
  the same numbers independently. A golden file captured from the program under test would only
  prove the program still behaves as it did last week; deriving the answer twice, in two languages,
  is what makes the check worth running. CI regenerates the sample to confirm it has not drifted,
  then diffs the built jar's output against it.

The sample exercises every outcome in the table above, including both trade directions.

## Performance

`./gradlew benchmark` scans generated universes and reports wall-clock time. Measured on an Apple
M2 Pro, JDK 21, over 50 rounds after 20 warmup rounds:

| Funds | Legs per fund | Median | p99 | Funds/sec |
| --- | --- | --- | --- | --- |
| 100 | 10 | 0.28 ms | 0.67 ms | 362,000 |
| 500 | 25 | 2.11 ms | 2.78 ms | 237,000 |
| 2,000 | 50 | 12.30 ms | 14.39 ms | 163,000 |
| 5,000 | 100 | 73.32 ms | 86.92 ms | 68,000 |

A scan is linear in the total number of basket legs, and look-through expansion is memoised per
universe, so a constituent shared by many funds is expanded once.

## Design

The pipeline is a straight line, and only the ends touch the filesystem:

```text
CLI -> readers -> Universe -> ArbitrageScanner -> Opportunity -> writer -> file
```

- `cli` argument parsing, exit codes, and IO wiring
- `io.input` CSV readers, which also reject circular baskets
- `io.output` renders the report
- `domain.model` immutable value types: `Quote`, `Level`, `Constituent`, `Etf`, `Universe`
- `domain.pricing` `LookThrough`, `ArbitrageScanner`, `FeeSchedule`, `QuotePolicy`, `Opportunity`
- `error` a typed exception carrying the source file and line

The scanner is pure and takes its policy by constructor, so a test states a book and a fee
schedule and gets an answer with no IO involved.

Longer notes on the modelling decisions are in [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md).

## Limitations

- Top of book only. Real execution would walk deeper levels, and size taken from the touch alone
  is optimistic for anything larger than the quoted quantity.
- A snapshot, not a feed. The scanner recomputes a whole universe from scratch; it does not update
  incrementally as quotes arrive.
- Prices are `double`. Fine for the two-decimal prices here, but a production system handling
  arbitrary decimals would use integer minor units.
- No borrow, settlement or market impact. The redeem direction assumes the basket can be sold
  outright, and neither direction models the delay between legging in and the fund's exchange.
- The creation fee is a single flat number. Real schedules vary by fund and often by unit count.

## Possible extensions

- Incremental updates: recompute only the funds touched by each quote rather than the whole
  universe, and report per-update latency.
- Depth-aware sizing: walk the book rather than stopping at the touch.
- Multi-venue quotes, choosing the best leg per venue and charging the fee schedule of each.
