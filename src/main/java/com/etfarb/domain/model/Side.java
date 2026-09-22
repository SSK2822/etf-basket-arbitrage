package com.etfarb.domain.model;

/** Which side of a two-sided quote a trade would take. */
public enum Side {
  /** Buying: lift the offer, paying the ask. */
  BUY,
  /** Selling: hit the bid, receiving the bid. */
  SELL
}
