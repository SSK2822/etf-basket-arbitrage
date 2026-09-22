package com.etfarb.domain.pricing;

import com.etfarb.domain.model.Etf;
import com.etfarb.domain.model.Level;
import com.etfarb.domain.model.Quote;
import com.etfarb.domain.model.Side;
import com.etfarb.domain.model.Universe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scans a universe for creation and redemption arbitrage.
 *
 * <p>For each ETF the scanner values the creation basket on both sides of the market and compares
 * it against the ETF's own quote:
 *
 * <ul>
 *   <li><b>Create</b> when the ETF is rich: buy the basket at the offer, deliver it, and sell the
 *       ETF shares received at the bid.
 *   <li><b>Redeem</b> when the ETF is cheap: buy the ETF at the offer, redeem it, and sell the
 *       basket at the bid.
 * </ul>
 *
 * <p>Both directions cross the spread on every leg rather than marking to mid, so the edge that
 * comes out is one that could have been taken, not a mid-price illusion. It is then sized by the
 * quantity resting on the thinnest leg and charged the full {@link FeeSchedule}. An ETF only
 * produces a {@link Opportunity.Trade} if what survives all of that clears {@code minEdge}.
 */
public final class ArbitrageScanner {

  private final QuotePolicy policy;
  private final FeeSchedule fees;
  private final double minEdge;

  public ArbitrageScanner(QuotePolicy policy, FeeSchedule fees, double minEdge) {
    if (!Double.isFinite(minEdge) || minEdge < 0) {
      throw new IllegalArgumentException("min edge must be finite and non-negative: " + minEdge);
    }
    this.policy = policy;
    this.fees = fees;
    this.minEdge = minEdge;
  }

  /** One verdict per ETF in the universe's scan order. */
  public List<Opportunity> scan(Universe universe) {
    LookThrough lookThrough = new LookThrough(universe);
    List<Opportunity> results = new ArrayList<>(universe.scanOrder().size());
    for (Etf etf : universe.scanTargets()) {
      results.add(evaluate(universe, lookThrough, etf));
    }
    return List.copyOf(results);
  }

  private Opportunity evaluate(Universe universe, LookThrough lookThrough, Etf etf) {
    LookThrough.Resolution resolution = lookThrough.resolve(etf);
    if (!resolution.isResolved()) {
      return new Opportunity.Skip(etf.symbol(), resolution.failure());
    }

    Quote etfQuote = universe.quote(etf.symbol()).orElse(null);
    if (etfQuote == null) {
      return new Opportunity.Skip(etf.symbol(), SkipReason.UNQUOTED_ETF);
    }
    Optional<SkipReason> rejected = policy.reject(etfQuote, universe.asOf());
    if (rejected.isPresent()) {
      return new Opportunity.Skip(etf.symbol(), rejected.get());
    }

    Candidate create = candidate(universe, etf, resolution.leaves(), etfQuote, Direction.CREATE);
    Candidate redeem = candidate(universe, etf, resolution.leaves(), etfQuote, Direction.REDEEM);

    return choose(etf, create, redeem);
  }

  /**
   * Prices one direction. The basket is traded on one side and the ETF on the other, so the sides
   * are always opposed: creating buys the basket and sells the ETF, redeeming does the reverse.
   */
  private Candidate candidate(
      Universe universe, Etf etf, Map<String, Long> leaves, Quote etfQuote, Direction direction) {

    Side basketSide = direction == Direction.CREATE ? Side.BUY : Side.SELL;
    Side etfSide = direction == Direction.CREATE ? Side.SELL : Side.BUY;

    Valued basket = valueBasket(universe, leaves, basketSide);
    if (basket.failure() != null) {
      return Candidate.unavailable(basket.failure());
    }

    Level etfLevel = etfQuote.level(etfSide).orElse(null);
    if (etfLevel == null) {
      return Candidate.unavailable(SkipReason.ONE_SIDED_QUOTE);
    }

    double etfNotional = etfLevel.notional(etf.unitSize());
    double basketNotional = basket.valuation().notionalPerUnit();

    double gross =
        direction == Direction.CREATE ? etfNotional - basketNotional : basketNotional - etfNotional;

    long etfUnits = etfLevel.size() / etf.unitSize();
    long units = Math.min(basket.valuation().maxUnits(), etfUnits);
    String limiting =
        etfUnits <= basket.valuation().maxUnits()
            ? etf.symbol()
            : basket.valuation().limitingSymbol();

    double feesPerUnit = fees.costOn(etfNotional + basketNotional);
    return Candidate.priced(direction, gross, feesPerUnit, units, limiting);
  }

  /** Values every leaf on one side, and caps units at the thinnest leg. */
  private Valued valueBasket(Universe universe, Map<String, Long> leaves, Side side) {
    double notional = 0;
    long maxUnits = Long.MAX_VALUE;
    String limiting = null;

    for (Map.Entry<String, Long> leaf : leaves.entrySet()) {
      String symbol = leaf.getKey();
      long quantity = leaf.getValue();

      Quote quote = universe.quote(symbol).orElse(null);
      if (quote == null) {
        return Valued.failed(SkipReason.UNPRICED_CONSTITUENT);
      }
      Optional<SkipReason> rejected = policy.reject(quote, universe.asOf());
      if (rejected.isPresent()) {
        return Valued.failed(rejected.get());
      }
      Level level = quote.level(side).orElse(null);
      if (level == null) {
        return Valued.failed(SkipReason.ONE_SIDED_QUOTE);
      }

      notional += level.notional(quantity);
      long units = level.size() / quantity;
      if (units < maxUnits) {
        maxUnits = units;
        limiting = symbol;
      }
    }
    return Valued.of(new BasketValuation(notional, maxUnits, limiting));
  }

  /**
   * Picks between the two directions. At most one can show a gross edge unless the book is crossed,
   * which the policy has already ruled out, so the richer one wins outright.
   */
  private Opportunity choose(Etf etf, Candidate create, Candidate redeem) {
    Candidate best = create.grossEdge() >= redeem.grossEdge() ? create : redeem;

    if (!create.isPriced() && !redeem.isPriced()) {
      return new Opportunity.Skip(
          etf.symbol(), moreInformative(create.failure(), redeem.failure()));
    }
    if (!best.isPriced() || best.grossEdge() <= 0) {
      return new Opportunity.Skip(etf.symbol(), SkipReason.NO_EDGE);
    }
    if (best.units() <= 0) {
      return new Opportunity.Skip(etf.symbol(), SkipReason.NO_EXECUTABLE_SIZE);
    }

    Opportunity.Trade trade =
        new Opportunity.Trade(
            etf.symbol(),
            best.direction(),
            best.units(),
            best.grossEdge(),
            best.feesPerUnit(),
            best.limitingSymbol());

    return trade.netEdgeTotal() >= minEdge && trade.netEdgePerUnit() > 0
        ? trade
        : new Opportunity.Skip(etf.symbol(), SkipReason.EDGE_BELOW_THRESHOLD);
  }

  /**
   * When both directions fail, prefer the reason that says something about the data over a bare
   * one-sided market, which is the least useful thing to report.
   */
  private static SkipReason moreInformative(SkipReason first, SkipReason second) {
    return first == SkipReason.ONE_SIDED_QUOTE ? second : first;
  }

  /** A basket valuation, or the reason it could not be produced. */
  private record Valued(BasketValuation valuation, SkipReason failure) {

    static Valued of(BasketValuation valuation) {
      return new Valued(valuation, null);
    }

    static Valued failed(SkipReason reason) {
      return new Valued(null, reason);
    }
  }

  /** One direction, priced and sized, or unavailable with a reason. */
  private record Candidate(
      Direction direction,
      double grossEdge,
      double feesPerUnit,
      long units,
      String limitingSymbol,
      SkipReason failure) {

    static Candidate priced(
        Direction direction, double gross, double fees, long units, String limiting) {
      return new Candidate(direction, gross, fees, units, limiting, null);
    }

    static Candidate unavailable(SkipReason reason) {
      return new Candidate(null, Double.NEGATIVE_INFINITY, 0, 0, null, reason);
    }

    boolean isPriced() {
      return failure == null;
    }
  }
}
