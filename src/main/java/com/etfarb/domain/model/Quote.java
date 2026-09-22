package com.etfarb.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A top-of-book quote for one symbol, as of a point in time.
 *
 * <p>Either side may be absent, which models a one-sided market. A quote is only usable for a trade
 * if the side that trade needs is present, the book is not crossed, and the quote is recent enough
 * for the caller's tolerance.
 */
public record Quote(String symbol, Optional<Level> bid, Optional<Level> ask, Instant asOf) {

  public Quote {
    if (symbol == null || symbol.isBlank()) {
      throw new IllegalArgumentException("quote symbol must not be blank");
    }
    if (bid == null || ask == null) {
      throw new IllegalArgumentException("quote sides must not be null");
    }
    if (asOf == null) {
      throw new IllegalArgumentException("quote requires an as-of timestamp");
    }
  }

  /** The level a trade on {@code side} would execute against, if there is one. */
  public Optional<Level> level(Side side) {
    return side == Side.BUY ? ask : bid;
  }

  /**
   * True when both sides are present and the bid is at or above the ask. A crossed book is a data
   * fault rather than free money, so the scanner refuses to trade on it.
   */
  public boolean isCrossed() {
    return bid.isPresent() && ask.isPresent() && bid.get().price() >= ask.get().price();
  }

  /** True when this quote is older than {@code maxAge} relative to {@code now}. */
  public boolean isStale(Instant now, Duration maxAge) {
    return asOf.isBefore(now.minus(maxAge));
  }
}
