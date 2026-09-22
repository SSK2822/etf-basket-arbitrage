package com.etfarb.bench;

import com.etfarb.domain.model.Constituent;
import com.etfarb.domain.model.Etf;
import com.etfarb.domain.model.Level;
import com.etfarb.domain.model.Quote;
import com.etfarb.domain.model.Universe;
import com.etfarb.domain.pricing.ArbitrageScanner;
import com.etfarb.domain.pricing.FeeSchedule;
import com.etfarb.domain.pricing.Opportunity;
import com.etfarb.domain.pricing.QuotePolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Measures how long a full scan takes on a generated universe.
 *
 * <p>This is a wall-clock harness, not a microbenchmark: it reports the cost of the operation the
 * program actually performs, which is scanning every fund in a snapshot once. Results are printed
 * rather than asserted, because a throughput number that fails the build on a loaded machine is
 * worse than no number at all.
 *
 * <p>Run with {@code ./gradlew benchmark}.
 */
public final class ScanBenchmark {

  private static final long SEED = 20260921L;
  private static final int WARMUP_ROUNDS = 20;
  private static final int MEASURED_ROUNDS = 50;

  private ScanBenchmark() {}

  public static void main(String[] args) {
    System.out.printf(
        Locale.ROOT,
        "%-10s %10s %12s %12s %14s%n",
        "funds",
        "legs/fund",
        "median ms",
        "p99 ms",
        "funds/sec");

    for (int[] shape : new int[][] {{100, 10}, {500, 25}, {2_000, 50}, {5_000, 100}}) {
      run(shape[0], shape[1]);
    }
  }

  private static void run(int funds, int legsPerFund) {
    Universe universe = generate(funds, legsPerFund);
    ArbitrageScanner scanner = new ArbitrageScanner(QuotePolicy.PERMISSIVE, FeeSchedule.FREE, 0);

    long blackhole = 0;
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      blackhole += scanner.scan(universe).size();
    }

    long[] timings = new long[MEASURED_ROUNDS];
    for (int i = 0; i < MEASURED_ROUNDS; i++) {
      long start = System.nanoTime();
      List<Opportunity> results = scanner.scan(universe);
      timings[i] = System.nanoTime() - start;
      blackhole += results.size();
    }

    java.util.Arrays.sort(timings);
    double medianMs = timings[MEASURED_ROUNDS / 2] / 1e6;
    double p99Ms = timings[(int) (MEASURED_ROUNDS * 0.99)] / 1e6;

    System.out.printf(
        Locale.ROOT,
        "%-10d %10d %12.2f %12.2f %14.0f%n",
        funds,
        legsPerFund,
        medianMs,
        p99Ms,
        funds / (medianMs / 1000));

    if (blackhole == Long.MIN_VALUE) {
      throw new AssertionError("unreachable, but keeps the scan from being optimised away");
    }
  }

  /**
   * A universe of {@code funds} funds over a shared pool of constituents, so the look-through cache
   * is exercised the way it would be on a real feed rather than on disjoint baskets.
   */
  private static Universe generate(int funds, int legsPerFund) {
    Random random = new Random(SEED);
    Instant asOf = Instant.parse("2026-09-21T14:30:00Z");
    int poolSize = Math.max(legsPerFund * 4, 200);

    Map<String, Quote> quotes = new HashMap<>();
    for (int i = 0; i < poolSize; i++) {
      quotes.put("L" + i, quote("L" + i, 10 + random.nextDouble() * 500, asOf));
    }

    Map<String, Etf> etfs = new HashMap<>();
    List<String> scanOrder = new ArrayList<>(funds);
    for (int i = 0; i < funds; i++) {
      String symbol = "F" + i;
      List<Constituent> basket = new ArrayList<>(legsPerFund);
      double fairPerUnit = 0;
      for (int leg = 0; leg < legsPerFund; leg++) {
        String constituent = "L" + random.nextInt(poolSize);
        if (basket.stream().anyMatch(c -> c.symbol().equals(constituent))) {
          continue;
        }
        long quantity = 1 + random.nextInt(100);
        basket.add(new Constituent(constituent, quantity));
        fairPerUnit += quotes.get(constituent).ask().orElseThrow().price() * quantity;
      }

      long unitSize = 100;
      // Half the funds are quoted away from fair value, so both branches of the scan run.
      double skew = i % 2 == 0 ? 1.002 : 0.998;
      quotes.put(symbol, quote(symbol, fairPerUnit / unitSize * skew, asOf));
      etfs.put(symbol, new Etf(symbol, unitSize, basket));
      scanOrder.add(symbol);
    }

    return new Universe(quotes, etfs, scanOrder, asOf);
  }

  private static Quote quote(String symbol, double mid, Instant asOf) {
    double half = Math.max(0.01, mid * 0.0002);
    return new Quote(
        symbol,
        Optional.of(new Level(round(mid - half), 1_000_000)),
        Optional.of(new Level(round(mid + half), 1_000_000)),
        asOf);
  }

  private static double round(double value) {
    return Math.max(0.01, Math.round(value * 100) / 100.0);
  }
}
