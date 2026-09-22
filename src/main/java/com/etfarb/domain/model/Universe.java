package com.etfarb.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the scanner needs for one pass: the quotes that were in force, the ETFs whose baskets
 * are known, and the order in which to scan them.
 *
 * <p>{@code asOf} is the reference time the quotes are judged against for staleness, normally the
 * newest timestamp in the feed. Symbols can appear as a quote, as an ETF, or as both: an ETF that
 * is itself quoted trades directly, and one that is not can still be valued by looking through to
 * its own basket.
 */
public record Universe(
    Map<String, Quote> quotes,
    Map<String, Etf> etfs,
    List<String> scanOrder,
    java.time.Instant asOf) {

  public Universe {
    quotes = Map.copyOf(quotes);
    etfs = Map.copyOf(etfs);
    scanOrder = List.copyOf(scanOrder);
    if (asOf == null) {
      throw new IllegalArgumentException("universe requires an as-of timestamp");
    }
    for (String symbol : scanOrder) {
      if (!etfs.containsKey(symbol)) {
        throw new IllegalArgumentException("scan order references unknown etf '" + symbol + "'");
      }
    }
  }

  public Optional<Quote> quote(String symbol) {
    return Optional.ofNullable(quotes.get(symbol));
  }

  public Optional<Etf> etf(String symbol) {
    return Optional.ofNullable(etfs.get(symbol));
  }

  /** The ETFs to scan, resolved from {@link #scanOrder}, in input order. */
  public List<Etf> scanTargets() {
    return scanOrder.stream().map(etfs::get).toList();
  }
}
