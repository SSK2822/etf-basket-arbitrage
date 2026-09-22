package com.etfarb.domain.pricing;

/** Why an ETF produced no actionable opportunity on this pass. */
public enum SkipReason {
  /** The two sides were priced consistently: no edge in either direction. */
  NO_EDGE,
  /** There was an edge, but fees and the spread left too little of it. */
  EDGE_BELOW_THRESHOLD,
  /** The ETF itself is not quoted, so neither leg of the round trip can be traded. */
  UNQUOTED_ETF,
  /** A constituent had neither a quote of its own nor a basket to look through to. */
  UNPRICED_CONSTITUENT,
  /** A constituent holding of an unquoted sub-fund was not a whole number of its creation units. */
  INDIVISIBLE_LOOKTHROUGH,
  /** A quote needed for this ETF was older than the configured tolerance. */
  STALE_QUOTE,
  /** A quote needed for this ETF had its bid at or above its ask. */
  CROSSED_QUOTE,
  /** Neither direction could be traded, because the side it needs has nothing resting on it. */
  ONE_SIDED_QUOTE,
  /** The edge was real but nothing could be executed against it in size. */
  NO_EXECUTABLE_SIZE
}
