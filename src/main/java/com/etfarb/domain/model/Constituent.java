package com.etfarb.domain.model;

/** One line of a creation basket: {@code quantity} shares of {@code symbol} per creation unit. */
public record Constituent(String symbol, long quantity) {

  public Constituent {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("constituent symbol must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("constituent quantity must be positive: " + quantity);
    }
  }
}
