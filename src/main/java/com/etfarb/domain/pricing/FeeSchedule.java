package com.etfarb.domain.pricing;

/**
 * The cost of doing the trade, in the two forms that actually bite on a basket arbitrage.
 *
 * <p>{@code commissionBps} is charged on every dollar that changes hands, on both the ETF leg and
 * the basket leg. {@code unitFee} is the flat charge the fund levies per creation unit, which is
 * what usually decides whether a small edge is worth taking.
 */
public record FeeSchedule(double commissionBps, double unitFee) {

  /** No commission and no unit fee, for tests and for an unconstrained upper bound. */
  public static final FeeSchedule FREE = new FeeSchedule(0, 0);

  public FeeSchedule {
    if (!Double.isFinite(commissionBps) || commissionBps < 0) {
      throw new IllegalArgumentException(
          "commission bps must be finite and non-negative: " + commissionBps);
    }
    if (!Double.isFinite(unitFee) || unitFee < 0) {
      throw new IllegalArgumentException("unit fee must be finite and non-negative: " + unitFee);
    }
  }

  /** Total cost of turning over {@code notional} dollars across both legs of one creation unit. */
  public double costOn(double notional) {
    return notional * commissionBps / 10_000.0 + unitFee;
  }
}
