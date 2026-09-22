package com.etfarb.domain.pricing;

/**
 * What one creation unit of a basket is worth on one side of the market, and how many units the
 * resting sizes support.
 *
 * <p>{@code limitingSymbol} is the leg whose available size caps {@code maxUnits}.
 */
public record BasketValuation(double notionalPerUnit, long maxUnits, String limitingSymbol) {}
