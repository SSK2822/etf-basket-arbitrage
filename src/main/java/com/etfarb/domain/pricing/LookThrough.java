package com.etfarb.domain.pricing;

import com.etfarb.domain.model.Constituent;
import com.etfarb.domain.model.Etf;
import com.etfarb.domain.model.Universe;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reduces a creation basket to the symbols that can actually be traded.
 *
 * <p>A constituent that is quoted is a leaf: the arbitrageur buys or sells those shares directly. A
 * constituent that is an unquoted sub-fund is expanded into its own basket instead, because the
 * only way to get those shares is to assemble them. Expansion is scaled by whole creation units of
 * the sub-fund, so a holding that is not a multiple of the sub-fund's unit size cannot be
 * replicated and the whole ETF is skipped.
 *
 * <p>Results are memoised per universe, so a constituent shared by many ETFs is expanded once.
 */
public final class LookThrough {

  private final Universe universe;
  private final Map<String, Resolution> cache = new HashMap<>();

  public LookThrough(Universe universe) {
    this.universe = universe;
  }

  /** The tradable leaf quantities per creation unit of {@code etf}, or why they cannot be found. */
  public Resolution resolve(Etf etf) {
    Map<String, Long> leaves = new LinkedHashMap<>();
    SkipReason failure = expand(etf, 1, leaves);
    return failure == null
        ? new Resolution(Map.copyOf(leaves), null)
        : new Resolution(null, failure);
  }

  /** Accumulates {@code units} creation units of {@code etf} into {@code leaves}. */
  private SkipReason expand(Etf etf, long units, Map<String, Long> leaves) {
    for (Constituent constituent : etf.basket()) {
      long needed = constituent.quantity() * units;
      String symbol = constituent.symbol();

      if (universe.quote(symbol).isPresent()) {
        leaves.merge(symbol, needed, Long::sum);
        continue;
      }

      Etf subFund = universe.etf(symbol).orElse(null);
      if (subFund == null) {
        return SkipReason.UNPRICED_CONSTITUENT;
      }
      if (needed % subFund.unitSize() != 0) {
        return SkipReason.INDIVISIBLE_LOOKTHROUGH;
      }
      SkipReason failure = expand(subFund, needed / subFund.unitSize(), leaves);
      if (failure != null) {
        return failure;
      }
    }
    return null;
  }

  /** Either the resolved leaf quantities per creation unit, or the reason resolution failed. */
  public record Resolution(Map<String, Long> leaves, SkipReason failure) {

    public boolean isResolved() {
      return failure == null;
    }
  }
}
