package com.etfarb.domain.model;

/**
 * One side of a quote: a price and the quantity available at it.
 *
 * <p>Both fields must be strictly positive. A side with nothing resting on it is modelled as an
 * absent {@link Level} rather than a zero, so a one-sided market is explicit in the type.
 */
public record Level(double price, long size) {

  public Level {
    if (!Double.isFinite(price) || price <= 0) {
      throw new IllegalArgumentException("level price must be finite and positive: " + price);
    }
    if (size <= 0) {
      throw new IllegalArgumentException("level size must be positive: " + size);
    }
  }

  /** Notional value of {@code quantity} units at this price. */
  public double notional(long quantity) {
    return price * quantity;
  }
}
