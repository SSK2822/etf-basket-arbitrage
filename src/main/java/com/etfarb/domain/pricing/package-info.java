/**
 * Turning a market snapshot into sized, costed trades: {@link
 * com.etfarb.domain.pricing.LookThrough} reduces a basket to tradable legs, {@link
 * com.etfarb.domain.pricing.ArbitrageScanner} prices both directions against the ETF's own quote,
 * and {@link com.etfarb.domain.pricing.Opportunity} carries the verdict.
 */
package com.etfarb.domain.pricing;
