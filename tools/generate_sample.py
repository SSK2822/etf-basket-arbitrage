#!/usr/bin/env python3
"""Generates the sample snapshot and the report the scanner is expected to produce.

The point of this script is that the expected report is worked out here, from the
prices, rather than captured from a run of the scanner. A golden file recorded from
the program under test only ever proves the program still does what it did last week.
Deriving the same answer twice, independently and in another language, is what makes
the sample worth checking against.

Fund quotes are not hand-written either. Each fund is quoted at the fair value of its
own basket, taken from constituent mids, then moved by a stated number of basis points.
That is what makes the sample readable: every dislocation in it is deliberate and
stated in one place, and the leaf prices can be reshuffled by changing the seed without
any of the expected answers having to be re-typed.

    python3 tools/generate_sample.py           # write the sample files
    python3 tools/generate_sample.py --check    # fail if the committed files differ
"""

from __future__ import annotations

import argparse
import random
import sys
from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

SEED = 20260921
AS_OF = "2026-09-21T14:30:02Z"
STALE_AS_OF = "2026-09-20T19:55:00Z"
SAMPLE_DIR = Path(__file__).resolve().parent.parent / "src/test/resources/sample"

# Scanner defaults, mirrored here so the expected report reflects a plain invocation.
FEE_BPS = 0.5
UNIT_FEE = 500.0
MIN_EDGE = 0.01
MAX_QUOTE_AGE_SECONDS = 5


@dataclass(frozen=True)
class Leaf:
    """A directly quoted symbol. A side is dropped by setting its size to zero."""

    price_hint: float
    bid_size: int
    ask_size: int
    spread_bps: float = 4.0
    note: str = ""


@dataclass(frozen=True)
class Fund:
    """A fund, its creation basket, and how far its quote sits from that basket."""

    unit_size: int
    basket: tuple[tuple[str, int], ...]
    skew_bps: float | None  # None means the fund is never quoted
    size: int = 1000
    spread_bps: float = 4.0
    bid: bool = True
    ask: bool = True
    stale: bool = False
    note: str = ""


# Leaf prices are drawn around a hint, so a new seed reshuffles the snapshot without
# changing what any case is meant to demonstrate.
LEAVES: dict[str, Leaf] = {
    "NVDX": Leaf(182.0, 4000, 3800, note="Liquid."),
    "ARTM": Leaf(64.0, 9000, 8600, note="Liquid."),
    "KELV": Leaf(311.0, 1500, 1400, note="Thinner, and usually the binding leg of CMPX."),
    "BRWK": Leaf(27.6, 22000, 21000),
    "SOLW": Leaf(95.7, 5200, 5000),
    "HDGE": Leaf(48.0, 7400, 7100),
    "TUNDR": Leaf(12.9, 60000, 58000),
    "SLIVR": Leaf(205.0, 150, 140, note="Too thin to fill even one creation unit of TINYX."),
    "PINCH": Leaf(88.4, 0, 3000, note="Offer only: nothing resting on the bid."),
}

FUNDS: dict[str, Fund] = {
    "CMPX": Fund(100, (("NVDX", 300), ("ARTM", 400), ("KELV", 200)), 60,
                 size=900, note="Quoted rich against its basket: create."),
    "CLNX": Fund(100, (("SOLW", 200), ("BRWK", 500), ("TUNDR", 1000)), -180,
                 size=1200, note="Quoted cheap against its basket: redeem."),
    "BALX": Fund(100, (("NVDX", 100), ("SOLW", 200), ("HDGE", 400)), 20,
                 size=2000, note="Barely off fair value; the unit fee should eat the edge."),
    "STACKX": Fund(100, (("CMPX", 200), ("HDGE", 300)), -150,
                   size=600, note="Fund of funds, holding whole creation units of quoted CMPX."),
    "WRAPX": Fund(100, (("SEEDX", 200), ("HDGE", 100)), 0, size=1000, spread_bps=12.0,
                  note="Holds unquoted SEEDX, valued by looking through to its basket."),
    "DRIFTX": Fund(100, (("NVDX", 150), ("ARTM", 250)), 80, size=1000, stale=True,
                   note="Rich, but the print is well behind the rest of the feed."),
    "THINX": Fund(100, (("KELV", 50), ("BRWK", 300)), -320, size=500, bid=False,
                  note="Cheap and offer only: still redeemable, but not creatable."),
    "TINYX": Fund(100, (("SLIVR", 1000), ("HDGE", 200)), 90, size=4000,
                  note="Real edge, no size behind it."),
    "LOPSX": Fund(100, (("PINCH", 400), ("NVDX", 100)), 50, size=800, bid=False,
                  note="Offer only, and holds an offer-only constituent: neither leg completes."),
    "SEEDX": Fund(100, (("BRWK", 400), ("TUNDR", 800)), None,
                  note="Never quoted; exists only to be looked through."),
    "FRACX": Fund(100, (("SEEDX", 150), ("HDGE", 100)), 40,
                  size=700, note="Holds a part creation unit of unquoted SEEDX."),
    "ORPHX": Fund(100, (("NVDX", 100), ("ZZAP", 250)), 30,
                  size=700, note="Holds ZZAP, which the feed never quotes."),
}


def cents(value: float) -> float:
    """Round to the cent the way Java's %.2f does, so both sides agree on the boundary."""
    return float(Decimal(repr(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def money(value: float) -> str:
    return f"{cents(value):.2f}"


# ---------------------------------------------------------------------------
# Building the snapshot
# ---------------------------------------------------------------------------


def build_quotes() -> dict[str, dict]:
    rng = random.Random(SEED)
    quotes: dict[str, dict] = {}

    for symbol, leaf in LEAVES.items():
        mid = leaf.price_hint * (1 + rng.uniform(-0.01, 0.01))
        half = mid * leaf.spread_bps / 2 / 10_000
        quotes[symbol] = two_sided(mid, half, leaf.bid_size, leaf.ask_size, AS_OF)

    # Funds are priced off their own basket, so this pass has to follow the leaves.
    for symbol, fund in FUNDS.items():
        if fund.skew_bps is None:
            continue
        per_share = fair_value_per_unit(symbol, quotes) / fund.unit_size
        per_share *= 1 + fund.skew_bps / 10_000
        half = per_share * fund.spread_bps / 2 / 10_000
        quotes[symbol] = two_sided(
            per_share,
            half,
            fund.size if fund.bid else 0,
            fund.size if fund.ask else 0,
            STALE_AS_OF if fund.stale else AS_OF,
        )
    return quotes


def two_sided(mid: float, half: float, bid_size: int, ask_size: int, as_of: str) -> dict:
    """A quote on the cent grid, never narrower than a tick."""
    ask = cents(mid + half)
    bid = min(cents(mid - half), cents(ask - 0.01))
    return {
        "bid": bid if bid_size > 0 else None,
        "bid_size": bid_size if bid_size > 0 else None,
        "ask": ask if ask_size > 0 else None,
        "ask_size": ask_size if ask_size > 0 else None,
        "as_of": as_of,
    }


def fair_value_per_unit(symbol: str, quotes: dict[str, dict]) -> float:
    """Value of one creation unit from constituent mids, looking through unquoted funds."""
    total = 0.0
    for constituent, quantity in FUNDS[symbol].basket:
        if constituent in quotes:
            total += mid_of(quotes[constituent]) * quantity
        elif constituent in FUNDS:
            sub = FUNDS[constituent]
            total += fair_value_per_unit(constituent, quotes) * quantity / sub.unit_size
        # An unknown symbol contributes nothing; such a fund is unpriceable anyway.
    return total


def mid_of(quote: dict) -> float:
    if quote["bid"] is None:
        return quote["ask"]
    if quote["ask"] is None:
        return quote["bid"]
    return (quote["bid"] + quote["ask"]) / 2


# ---------------------------------------------------------------------------
# Working out the expected report, independently of the scanner
# ---------------------------------------------------------------------------


def look_through(symbol: str, quotes: dict[str, dict]) -> tuple[dict[str, int] | None, str | None]:
    """Leaf quantities per creation unit, or the reason they cannot be worked out."""
    leaves: dict[str, int] = {}

    def expand(fund_symbol: str, units: int) -> str | None:
        for constituent, quantity in FUNDS[fund_symbol].basket:
            needed = quantity * units
            if constituent in quotes:
                leaves[constituent] = leaves.get(constituent, 0) + needed
            elif constituent in FUNDS:
                sub = FUNDS[constituent]
                if needed % sub.unit_size != 0:
                    return "INDIVISIBLE_LOOKTHROUGH"
                failure = expand(constituent, needed // sub.unit_size)
                if failure:
                    return failure
            else:
                return "UNPRICED_CONSTITUENT"
        return None

    failure = expand(symbol, 1)
    return (None, failure) if failure else (leaves, None)


def unusable(quote: dict, as_of: str) -> str | None:
    if quote["bid"] is not None and quote["ask"] is not None and quote["bid"] >= quote["ask"]:
        return "CROSSED_QUOTE"
    if seconds_between(quote["as_of"], as_of) > MAX_QUOTE_AGE_SECONDS:
        return "STALE_QUOTE"
    return None


def seconds_between(earlier: str, later: str) -> float:
    from datetime import datetime

    fmt = "%Y-%m-%dT%H:%M:%SZ"
    return (datetime.strptime(later, fmt) - datetime.strptime(earlier, fmt)).total_seconds()


def value_basket(leaves: dict[str, int], side: str, quotes: dict[str, dict], as_of: str):
    """Notional per unit and the units the resting sizes support, or a failure reason."""
    price_key, size_key = ("ask", "ask_size") if side == "buy" else ("bid", "bid_size")
    notional = 0.0
    max_units = None
    limiting = None

    for symbol, quantity in leaves.items():
        quote = quotes.get(symbol)
        if quote is None:
            return None, "UNPRICED_CONSTITUENT"
        reason = unusable(quote, as_of)
        if reason:
            return None, reason
        if quote[price_key] is None:
            return None, "ONE_SIDED_QUOTE"
        notional += quote[price_key] * quantity
        units = quote[size_key] // quantity
        if max_units is None or units < max_units:
            max_units, limiting = units, symbol

    return (notional, max_units, limiting), None


def candidate(symbol: str, leaves: dict[str, int], quotes: dict[str, dict], as_of: str, direction: str):
    fund = FUNDS[symbol]
    basket_side = "buy" if direction == "CREATE" else "sell"
    fund_key, fund_size_key = ("bid", "bid_size") if direction == "CREATE" else ("ask", "ask_size")

    valued, failure = value_basket(leaves, basket_side, quotes, as_of)
    if failure:
        return None, failure

    quote = quotes[symbol]
    if quote[fund_key] is None:
        return None, "ONE_SIDED_QUOTE"

    basket_notional, basket_units, basket_limiting = valued
    fund_notional = quote[fund_key] * fund.unit_size
    gross = (fund_notional - basket_notional) if direction == "CREATE" else (basket_notional - fund_notional)

    fund_units = quote[fund_size_key] // fund.unit_size
    units = min(basket_units, fund_units)
    limiting = symbol if fund_units <= basket_units else basket_limiting
    fees = (fund_notional + basket_notional) * FEE_BPS / 10_000 + UNIT_FEE

    return {"direction": direction, "gross": gross, "fees": fees, "units": units,
            "limiting": limiting}, None


def expected_row(symbol: str, quotes: dict[str, dict], as_of: str) -> str:
    def skip(reason: str) -> str:
        return f"{symbol},NONE,0,0.00,0.00,0.00,,{reason}"

    leaves, failure = look_through(symbol, quotes)
    if failure:
        return skip(failure)
    if symbol not in quotes:
        return skip("UNQUOTED_ETF")
    reason = unusable(quotes[symbol], as_of)
    if reason:
        return skip(reason)

    create, create_failure = candidate(symbol, leaves, quotes, as_of, "CREATE")
    redeem, redeem_failure = candidate(symbol, leaves, quotes, as_of, "REDEEM")

    if create is None and redeem is None:
        # A bare one-sided market says least about the data, so prefer the other reason.
        return skip(redeem_failure if create_failure == "ONE_SIDED_QUOTE" else create_failure)

    best = max(
        (c for c in (create, redeem) if c is not None),
        key=lambda c: c["gross"],
    )
    if best["gross"] <= 0:
        return skip("NO_EDGE")
    if best["units"] <= 0:
        return skip("NO_EXECUTABLE_SIZE")

    net_per_unit = best["gross"] - best["fees"]
    net_total = net_per_unit * best["units"]
    if net_per_unit <= 0 or net_total < MIN_EDGE:
        return skip("EDGE_BELOW_THRESHOLD")

    return ",".join([
        symbol,
        best["direction"],
        str(best["units"]),
        money(best["gross"]),
        money(best["fees"]),
        money(net_total),
        best["limiting"],
        "",
    ])


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def render_quotes(quotes: dict[str, dict]) -> str:
    lines = [
        "# Top of book for one snapshot. An empty price and size mean that side of the book is empty.",
        "# Generated by tools/generate_sample.py; edit that script rather than this file.",
        "symbol,bid,bid_size,ask,ask_size,as_of",
    ]
    for symbol, quote in quotes.items():
        note = LEAVES[symbol].note if symbol in LEAVES else FUNDS[symbol].note
        if note:
            lines.append("# " + note)
        lines.append(
            ",".join([
                symbol,
                "" if quote["bid"] is None else f"{quote['bid']:.2f}",
                "" if quote["bid_size"] is None else str(quote["bid_size"]),
                "" if quote["ask"] is None else f"{quote['ask']:.2f}",
                "" if quote["ask_size"] is None else str(quote["ask_size"]),
                quote["as_of"],
            ])
        )
    return "\n".join(lines) + "\n"


def render_baskets() -> str:
    lines = [
        "# One row per constituent. unit_size is the ETF shares exchanged for one creation unit.",
        "# Generated by tools/generate_sample.py; edit that script rather than this file.",
        "etf,unit_size,constituent,quantity",
    ]
    for symbol, fund in FUNDS.items():
        for constituent, quantity in fund.basket:
            lines.append(f"{symbol},{fund.unit_size},{constituent},{quantity}")
    return "\n".join(lines) + "\n"


def render_expected(quotes: dict[str, dict]) -> str:
    as_of = max(quote["as_of"] for quote in quotes.values())
    lines = ["etf,action,units,gross_edge,fees,net_edge,limiting_symbol,reason"]
    lines.extend(expected_row(symbol, quotes, as_of) for symbol in FUNDS)
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero if the committed files differ from what this run produces")
    args = parser.parse_args()

    quotes = build_quotes()
    outputs = {
        SAMPLE_DIR / "quotes.csv": render_quotes(quotes),
        SAMPLE_DIR / "baskets.csv": render_baskets(),
        SAMPLE_DIR / "expected-report.csv": render_expected(quotes),
    }

    if args.check:
        stale = [path.name for path, content in outputs.items()
                 if not path.exists() or path.read_text() != content]
        if stale:
            print("sample files are out of date: " + ", ".join(stale), file=sys.stderr)
            print("run: python3 tools/generate_sample.py", file=sys.stderr)
            return 1
        print("sample files are up to date")
        return 0

    SAMPLE_DIR.mkdir(parents=True, exist_ok=True)
    for path, content in outputs.items():
        path.write_text(content)
        print(f"wrote {path.relative_to(SAMPLE_DIR.parent.parent.parent.parent)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
