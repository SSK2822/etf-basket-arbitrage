# ETF Basket Arbitrage

[![ci](https://github.com/SSK2822/etf-basket-arbitrage/actions/workflows/ci.yml/badge.svg)](https://github.com/SSK2822/etf-basket-arbitrage/actions/workflows/ci.yml)
[![license: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)

A command line scanner that reads top-of-book quotes and ETF creation baskets, and reports where a
fund is priced far enough from its own basket to trade. For each fund it picks the direction, sizes
the trade against the quantity actually resting on the book, charges fees, and reports what
survives.

An ETF can be exchanged with the fund for the basket backing it, in blocks called creation units.
That gives two round trips: **create** when the fund is rich (buy the basket, deliver it, sell the
shares received) and **redeem** when it is cheap (buy the fund, redeem it, sell the basket).

Finding the dislocation is the easy part. The work is in deciding which ones are real:

- Every leg is priced at the side a trade would hit, never the mid.
- Size is capped by the thinnest leg, in whole creation units.
- Commission on both legs, plus the fund's flat per-unit fee, which is usually what decides a small
  dislocation.
- Stale, crossed and one-sided quotes are reported as reasons, not traded.
- An unquoted sub-fund is valued by looking through to its own basket.

Reasoning behind these is in [docs/DESIGN_NOTES.md](docs/DESIGN_NOTES.md).

## Usage

```console
java -jar build/libs/etf-arb-all.jar \
  --quotes quotes.csv --baskets baskets.csv --out report.csv
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--quotes` | required | CSV of top-of-book quotes |
| `--baskets` | required | CSV of creation baskets |
| `--out` | required | CSV report to write |
| `--fee-bps` | `0.5` | commission in basis points, charged on both legs |
| `--unit-fee` | `500` | flat fee per creation unit |
| `--max-quote-age` | `5` | seconds a quote stays tradable |
| `--min-edge` | `0.01` | smallest net edge worth reporting |

Exit codes: `0` success, `2` usage, `3` invalid input, `4` IO failure.

## Input

Quotes, one row per symbol. An empty price and size mean that side of the book is empty, which is
not the same as a price of zero.

```csv
symbol,bid,bid_size,ask,ask_size,as_of
NVDX,182.40,4000,182.46,3800,2026-09-21T14:30:02Z
PINCH,,,88.40,3000,2026-09-21T14:30:02Z
```

Creation baskets, one row per constituent. `unit_size` is the ETF shares exchanged for one creation
unit; `quantity` is constituent shares per unit. Funds are reported in the order they first appear.

```csv
etf,unit_size,constituent,quantity
CMPX,100,NVDX,300
CMPX,100,ARTM,400
```

Columns may be in any order; blank lines and `#` comments are ignored. Malformed input fails with
the file, line number and cause. Staleness is judged against the newest timestamp in the file
rather than the wall clock, so a captured snapshot replays identically.

## Output

```csv
etf,action,units,gross_edge,fees,net_edge,limiting_symbol,reason
CMPX,CREATE,7,793.00,514.25,1951.27,KELV,
CLNX,REDEEM,12,802.00,504.53,3569.64,CLNX,
BALX,NONE,0,0.00,0.00,0.00,,EDGE_BELOW_THRESHOLD
```

`gross_edge` and `fees` are per creation unit; `net_edge` is the whole trade after fees.
`limiting_symbol` is the leg that capped the size.

| Reason | Meaning |
| --- | --- |
| `NO_EDGE` | fund and basket are priced consistently |
| `EDGE_BELOW_THRESHOLD` | a dislocation, but fees left too little of it |
| `NO_EXECUTABLE_SIZE` | real edge, not enough resting size for one unit |
| `STALE_QUOTE` | a quote was older than `--max-quote-age` |
| `CROSSED_QUOTE` | a bid at or above its ask, which is a data fault |
| `ONE_SIDED_QUOTE` | neither direction had both legs quoted |
| `UNQUOTED_ETF` | the fund itself is not quoted |
| `UNPRICED_CONSTITUENT` | a constituent is neither quoted nor a fund with a known basket |
| `INDIVISIBLE_LOOKTHROUGH` | a holding of an unquoted sub-fund is not a whole number of its units |

## Build and test

```console
./gradlew build                 # compile, lint, test
./gradlew scanSample            # scan the sample, diff against the expected report
./gradlew benchmark             # measure scan throughput
./gradlew shadowJar             # build the runnable jar
```

Java 21 via a Gradle toolchain, so a newer local JDK is fine.

Tests come in three layers:

- **Hand-built books**, with the arithmetic in a comment and the answer following from it.
- **Randomised properties**, 6,000 generated books per run: a fund quoted inside its basket's spread
  never trades, one quoted away from it always trades in the right direction, and the edge never
  exceeds the dislocation. Fixed seed.
- **A derived sample.** [tools/generate_sample.py](tools/generate_sample.py) writes the snapshot and
  works out the expected report from the prices, in Python; the scanner has to reach the same
  numbers independently. A golden file captured from the program under test proves only that
  behaviour has not changed, and would enshrine a bug present on the day it was recorded. CI checks
  the sample has not drifted from the script, then diffs the built jar's output against it.

## Performance

`./gradlew benchmark`, on an Apple M2 Pro, JDK 21, 50 rounds after 20 warmup:

| Funds | Legs per fund | Median | p99 | Funds/sec |
| --- | --- | --- | --- | --- |
| 100 | 10 | 0.28 ms | 0.67 ms | 362,000 |
| 500 | 25 | 2.11 ms | 2.78 ms | 237,000 |
| 2,000 | 50 | 12.30 ms | 14.39 ms | 163,000 |
| 5,000 | 100 | 73.32 ms | 86.92 ms | 68,000 |

Linear in total basket legs; look-through is memoised per universe.

## Design

```text
CLI -> readers -> Universe -> ArbitrageScanner -> Opportunity -> writer -> file
```

Only the ends touch the filesystem. The scanner is pure and takes its policy and fee schedule by
constructor, so a test states a book and asserts an answer with no IO.

- `cli` arguments, exit codes, IO wiring
- `io.input` CSV readers, which also reject circular baskets
- `io.output` report rendering
- `domain.model` `Quote`, `Level`, `Constituent`, `Etf`, `Universe`
- `domain.pricing` `LookThrough`, `ArbitrageScanner`, `FeeSchedule`, `QuotePolicy`, `Opportunity`
- `error` typed exception carrying source file and line

## Limitations

- Top of book only; size from the touch is optimistic for anything larger.
- A snapshot, not a feed: the whole universe is recomputed rather than updated incrementally.
- Prices are `double`, fine at two decimals but not for arbitrary precision.
- No borrow, settlement or market impact, and a single flat creation fee per fund.

Natural extensions: incremental updates touching only affected funds, depth-aware sizing, and
multi-venue quotes with per-venue fees.
