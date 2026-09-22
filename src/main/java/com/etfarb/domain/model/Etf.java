package com.etfarb.domain.model;

import java.util.List;

/**
 * An exchange traded fund and the creation basket that backs it.
 *
 * <p>{@code unitSize} is the number of ETF shares exchanged for one creation unit, and each {@link
 * Constituent} quantity is per creation unit. Creating one unit means delivering the whole basket
 * and receiving {@code unitSize} ETF shares; redeeming is the reverse.
 */
public record Etf(String symbol, long unitSize, List<Constituent> basket) {

  public Etf {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("etf symbol must not be blank");
    }
    if (unitSize <= 0) {
      throw new IllegalArgumentException("etf unit size must be positive: " + unitSize);
    }
    basket = List.copyOf(basket);
    if (basket.isEmpty()) {
      throw new IllegalArgumentException("etf '" + symbol + "' has an empty creation basket");
    }
    long distinct = basket.stream().map(Constituent::symbol).distinct().count();
    if (distinct != basket.size()) {
      throw new IllegalArgumentException("etf '" + symbol + "' repeats a constituent symbol");
    }
    if (basket.stream().anyMatch(c -> c.symbol().equals(symbol))) {
      throw new IllegalArgumentException("etf '" + symbol + "' lists itself as a constituent");
    }
  }
}
