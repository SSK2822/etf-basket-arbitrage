package com.etfarb.domain.pricing;

import com.etfarb.domain.model.Constituent;
import com.etfarb.domain.model.Etf;
import com.etfarb.domain.model.Level;
import com.etfarb.domain.model.Quote;
import com.etfarb.domain.model.Universe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds small universes by hand, so a test can state the book and the expected answer inline. */
final class Books {

  static final Instant NOW = Instant.parse("2026-09-21T14:30:00Z");

  private final Map<String, Quote> quotes = new LinkedHashMap<>();
  private final Map<String, Etf> etfs = new LinkedHashMap<>();
  private final List<String> scanOrder = new ArrayList<>();

  static Books book() {
    return new Books();
  }

  Books quote(String symbol, double bid, long bidSize, double ask, long askSize) {
    return quote(symbol, bid, bidSize, ask, askSize, NOW);
  }

  Books quote(String symbol, double bid, long bidSize, double ask, long askSize, Instant asOf) {
    quotes.put(
        symbol,
        new Quote(
            symbol,
            Optional.of(new Level(bid, bidSize)),
            Optional.of(new Level(ask, askSize)),
            asOf));
    return this;
  }

  Books bidOnly(String symbol, double bid, long bidSize) {
    quotes.put(
        symbol, new Quote(symbol, Optional.of(new Level(bid, bidSize)), Optional.empty(), NOW));
    return this;
  }

  Books askOnly(String symbol, double ask, long askSize) {
    quotes.put(
        symbol, new Quote(symbol, Optional.empty(), Optional.of(new Level(ask, askSize)), NOW));
    return this;
  }

  /** Declares a fund. Arguments alternate symbol and quantity: {@code etf("X", 100, "A", 3)}. */
  Books etf(String symbol, long unitSize, Object... constituents) {
    List<Constituent> basket = new ArrayList<>();
    for (int i = 0; i < constituents.length; i += 2) {
      basket.add(
          new Constituent((String) constituents[i], ((Number) constituents[i + 1]).longValue()));
    }
    etfs.put(symbol, new Etf(symbol, unitSize, basket));
    scanOrder.add(symbol);
    return this;
  }

  Universe universe() {
    return new Universe(quotes, etfs, scanOrder, NOW);
  }

  /** Scans with no fees and no staleness limit, so a test sees the raw price relationship. */
  Opportunity scanOne() {
    return new ArbitrageScanner(QuotePolicy.PERMISSIVE, FeeSchedule.FREE, 0)
        .scan(universe())
        .getFirst();
  }

  Opportunity scanOne(QuotePolicy policy, FeeSchedule fees, double minEdge) {
    return new ArbitrageScanner(policy, fees, minEdge).scan(universe()).getFirst();
  }
}
