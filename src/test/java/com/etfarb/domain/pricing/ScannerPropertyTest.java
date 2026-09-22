package com.etfarb.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.etfarb.domain.model.Constituent;
import com.etfarb.domain.model.Etf;
import com.etfarb.domain.model.Level;
import com.etfarb.domain.model.Quote;
import com.etfarb.domain.model.Universe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Randomised checks on the scanner: a fund quoted consistently with its own basket must never
 * produce a trade, and a fund quoted away from it must produce one in the right direction.
 *
 * <p>Randomised over many shapes rather than asserted on one book, because the failure mode being
 * guarded against is a sign error or an off-by-one spread that a single hand-built case can easily
 * satisfy by accident. The seed is fixed so a failure is reproducible.
 */
class ScannerPropertyTest {

  private static final int CASES = 2_000;
  private static final long SEED = 20260921L;

  /** A generated book, together with the basket value the fund was quoted against. */
  private record Generated(Universe universe, double fairValuePerUnit) {}

  private static Generated generate(Random random, double skewBps, double halfSpreadBps) {
    int legs = 2 + random.nextInt(5);

    Map<String, Quote> quotes = new LinkedHashMap<>();
    List<Constituent> basket = new ArrayList<>();
    double fairPerUnit = 0;

    for (int i = 0; i < legs; i++) {
      String symbol = "L" + i;
      long quantity = 1 + random.nextInt(500);
      double mid = 10 + random.nextDouble() * 500;
      double half = mid * (random.nextDouble() * 5 + 1) / 10_000;

      quotes.put(symbol, twoSided(symbol, mid, half));
      basket.add(new Constituent(symbol, quantity));
      // Fair value is taken at the mid, which sits inside the prices a trade would pay.
      fairPerUnit += mid * quantity;
    }

    // Size the unit so the fund trades in a normal price range. A fund priced at a few
    // cents would lose more to the second decimal place than the dislocation being tested.
    long unitSize = unitSizeFor(fairPerUnit, 25 + random.nextInt(375));

    double fundMid = fairPerUnit / unitSize * (1 + skewBps / 10_000);
    quotes.put("FUND", twoSided("FUND", fundMid, fundMid * halfSpreadBps / 10_000));

    Etf etf = new Etf("FUND", unitSize, basket);
    Universe universe = new Universe(quotes, Map.of("FUND", etf), List.of("FUND"), Books.NOW);
    return new Generated(universe, fairPerUnit);
  }

  /**
   * A quote on the cent grid, never narrower than a tick. Rounding a sub-cent spread would
   * otherwise produce a locked or crossed book, which the scanner correctly refuses to price.
   */
  private static Quote twoSided(String symbol, double mid, double half) {
    double ask = round(mid + half);
    double bid = Math.min(round(mid - half), ask - 0.01);
    return new Quote(
        symbol,
        Optional.of(new Level(round(bid), 1_000_000)),
        Optional.of(new Level(ask, 1_000_000)),
        Books.NOW);
  }

  /** A round unit size that puts the fund's share price near {@code targetPerShare}. */
  private static long unitSizeFor(double fairPerUnit, double targetPerShare) {
    long tens = Math.round(fairPerUnit / targetPerShare / 10);
    return Math.max(10, tens * 10);
  }

  private static double round(double value) {
    return Math.round(value * 100) / 100.0;
  }

  private static List<Opportunity> scan(Universe universe) {
    return new ArbitrageScanner(QuotePolicy.PERMISSIVE, FeeSchedule.FREE, 0).scan(universe);
  }

  @Test
  @DisplayName("a fund quoted inside its basket's own spread never yields a trade")
  void consistentPricesYieldNoArbitrage() {
    Random random = new Random(SEED);
    for (int i = 0; i < CASES; i++) {
      // The fund is quoted at fair value with a spread wider than the basket's, so its bid
      // is below what the basket costs and its ask above what the basket fetches.
      Generated generated = generate(random, 0, 12);

      assertThat(scan(generated.universe()))
          .as("case %d should show no opportunity", i)
          .allSatisfy(o -> assertThat(o).isInstanceOf(Opportunity.Skip.class));
    }
  }

  @Test
  @DisplayName("a fund quoted rich is always a create, and one quoted cheap always a redeem")
  void dislocatedPricesYieldTheCorrectDirection() {
    Random random = new Random(SEED);
    for (int i = 0; i < CASES; i++) {
      boolean rich = i % 2 == 0;
      // 200 bps is far wider than the widest generated spread, so the sign is unambiguous.
      Generated generated = generate(random, rich ? 200 : -200, 4);

      Opportunity only = scan(generated.universe()).getFirst();
      assertThat(only)
          .as("case %d (%s) should be actionable, got %s", i, rich ? "rich" : "cheap", only)
          .isInstanceOf(Opportunity.Trade.class);
      assertThat(((Opportunity.Trade) only).direction())
          .isEqualTo(rich ? Direction.CREATE : Direction.REDEEM);
    }
  }

  @Test
  @DisplayName("reported edge is never larger than the dislocation that created it")
  void edgeNeverExceedsTheDislocation() {
    Random random = new Random(SEED);
    for (int i = 0; i < CASES; i++) {
      Generated generated = generate(random, 200, 4);
      Opportunity only = scan(generated.universe()).getFirst();
      if (only instanceof Opportunity.Trade trade) {
        // The fund was placed 200 bps away from its basket, so no more than that is there
        // to be had, and crossing two spreads must leave strictly less.
        assertThat(trade.grossEdgePerUnit())
            .as("case %d", i)
            .isLessThan(generated.fairValuePerUnit() * 200 / 10_000);
      }
    }
  }
}
