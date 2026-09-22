package com.etfarb.domain.pricing;

/** Which way the creation basket trade goes. */
public enum Direction {
  /** The ETF is rich: buy the basket, deliver it to the fund, and sell the ETF shares received. */
  CREATE,
  /** The ETF is cheap: buy the ETF, redeem it for the basket, and sell the basket. */
  REDEEM
}
