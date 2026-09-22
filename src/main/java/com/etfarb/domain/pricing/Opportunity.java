package com.etfarb.domain.pricing;

/** The scanner's verdict on one ETF: either a sized, costed trade, or a reason there is none. */
public sealed interface Opportunity permits Opportunity.Trade, Opportunity.Skip {

  String etf();

  /**
   * An executable creation or redemption round trip.
   *
   * <p>Every figure is per creation unit except {@link #netEdgeTotal()}. {@code units} is what can
   * actually be filled against the sizes resting on the book, and {@code limitingSymbol} is the leg
   * that ran out first, which is the one to widen if the trade is worth more size.
   */
  record Trade(
      String etf,
      Direction direction,
      long units,
      double grossEdgePerUnit,
      double feesPerUnit,
      String limitingSymbol)
      implements Opportunity {

    public Trade {
      if (etf == null || etf.isBlank()) {
        throw new IllegalArgumentException("trade etf must not be blank");
      }
      if (direction == null) {
        throw new IllegalArgumentException("trade requires a direction");
      }
      if (units <= 0) {
        throw new IllegalArgumentException("trade units must be positive: " + units);
      }
      if (!Double.isFinite(grossEdgePerUnit) || grossEdgePerUnit <= 0) {
        throw new IllegalArgumentException(
            "gross edge must be finite and positive: " + grossEdgePerUnit);
      }
      if (!Double.isFinite(feesPerUnit) || feesPerUnit < 0) {
        throw new IllegalArgumentException("fees must be finite and non-negative: " + feesPerUnit);
      }
      if (limitingSymbol == null || limitingSymbol.isBlank()) {
        throw new IllegalArgumentException("trade must name the limiting symbol");
      }
    }

    /** Edge per creation unit after fees. */
    public double netEdgePerUnit() {
      return grossEdgePerUnit - feesPerUnit;
    }

    /** Edge across the whole executable size, which is what the trade is actually worth. */
    public double netEdgeTotal() {
      return netEdgePerUnit() * units;
    }
  }

  /** No trade, with the reason recorded so the output explains itself. */
  record Skip(String etf, SkipReason reason) implements Opportunity {

    public Skip {
      if (etf == null || etf.isBlank()) {
        throw new IllegalArgumentException("skip etf must not be blank");
      }
      if (reason == null) {
        throw new IllegalArgumentException("skip requires a reason");
      }
    }
  }
}
