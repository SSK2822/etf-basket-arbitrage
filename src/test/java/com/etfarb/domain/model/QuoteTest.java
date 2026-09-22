package com.etfarb.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuoteTest {

  private static final Instant NOW = Instant.parse("2026-09-21T14:30:00Z");

  private static Quote quote(Double bid, Double ask, Instant asOf) {
    return new Quote(
        "AAA",
        Optional.ofNullable(bid).map(p -> new Level(p, 100)),
        Optional.ofNullable(ask).map(p -> new Level(p, 100)),
        asOf);
  }

  @Test
  void resolvesTheLevelATradeWouldHit() {
    Quote quote = quote(10.0, 10.5, NOW);
    assertThat(quote.level(Side.BUY)).contains(new Level(10.5, 100));
    assertThat(quote.level(Side.SELL)).contains(new Level(10.0, 100));
  }

  @Test
  void aMissingSideIsAbsentRatherThanZero() {
    assertThat(quote(null, 10.5, NOW).level(Side.SELL)).isEmpty();
  }

  @Test
  void detectsACrossedBook() {
    assertThat(quote(10.6, 10.5, NOW).isCrossed()).isTrue();
    assertThat(quote(10.5, 10.5, NOW).isCrossed()).isTrue();
    assertThat(quote(10.4, 10.5, NOW).isCrossed()).isFalse();
  }

  @Test
  void aOneSidedQuoteIsNeverCrossed() {
    assertThat(quote(null, 10.5, NOW).isCrossed()).isFalse();
  }

  @Test
  void stalenessIsMeasuredAgainstTheReferenceTime() {
    Quote old = quote(10.0, 10.5, NOW.minusSeconds(10));
    assertThat(old.isStale(NOW, Duration.ofSeconds(5))).isTrue();
    assertThat(old.isStale(NOW, Duration.ofSeconds(30))).isFalse();
  }

  @Test
  void rejectsNonPositivePricesAndSizes() {
    assertThatThrownBy(() -> new Level(0, 100)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Level(10, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Level(Double.NaN, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
