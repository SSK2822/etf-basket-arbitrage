package com.etfarb.domain.pricing;

import com.etfarb.domain.model.Quote;
import java.time.Duration;
import java.time.Instant;

/**
 * The rules a quote must satisfy before the scanner is willing to trade against it.
 *
 * <p>Bad market data looks exactly like free money: a stale print, a crossed book or a side that
 * has emptied out will all show an edge that cannot be executed. Filtering them here is what keeps
 * the scanner from emitting confident nonsense.
 */
public record QuotePolicy(Duration maxAge) {

  /** Accepts a quote of any age, for tests and for replaying a static snapshot. */
  public static final QuotePolicy PERMISSIVE = new QuotePolicy(Duration.ofDays(3650));

  public QuotePolicy {
    if (maxAge == null || maxAge.isNegative()) {
      throw new IllegalArgumentException("max quote age must be non-negative");
    }
  }

  /** Why this quote cannot be traded as of {@code now}, or empty when it is fine. */
  public java.util.Optional<SkipReason> reject(Quote quote, Instant now) {
    if (quote.isCrossed()) {
      return java.util.Optional.of(SkipReason.CROSSED_QUOTE);
    }
    if (quote.isStale(now, maxAge)) {
      return java.util.Optional.of(SkipReason.STALE_QUOTE);
    }
    return java.util.Optional.empty();
  }
}
