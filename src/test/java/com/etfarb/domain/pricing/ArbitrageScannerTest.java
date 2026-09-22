package com.etfarb.domain.pricing;

import static com.etfarb.domain.pricing.Books.book;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ArbitrageScannerTest {

  private static org.assertj.core.api.ObjectAssert<Opportunity.Trade> assertTrade(
      Opportunity opportunity) {
    return assertThat(opportunity).asInstanceOf(type(Opportunity.Trade.class));
  }

  private static void assertSkipped(Opportunity opportunity, SkipReason reason) {
    assertThat(opportunity)
        .asInstanceOf(type(Opportunity.Skip.class))
        .extracting(Opportunity.Skip::reason)
        .isEqualTo(reason);
  }

  @Nested
  @DisplayName("direction")
  class DirectionCases {

    @Test
    void createsWhenTheFundIsRichAgainstItsBasket() {
      // One unit is 10 shares. The basket costs 2 x 100.00 = 200.00 to buy; the fund's
      // 10 shares sell for 10 x 25.00 = 250.00, so creating locks in 50.00 a unit.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .quote("RICHX", 25.00, 1000, 25.10, 1000)
              .etf("RICHX", 10, "AAA", 2)
              .scanOne();

      assertTrade(result)
          .returns(Direction.CREATE, Opportunity.Trade::direction)
          .returns(50.0, t -> t.grossEdgePerUnit());
    }

    @Test
    void redeemsWhenTheFundIsCheapAgainstItsBasket() {
      // Buying 10 fund shares costs 10 x 18.00 = 180.00; selling the basket it redeems
      // into returns 2 x 99.90 = 199.80, so redeeming locks in 19.80 a unit.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .quote("CHEAPX", 17.90, 1000, 18.00, 1000)
              .etf("CHEAPX", 10, "AAA", 2)
              .scanOne();

      assertTrade(result)
          .returns(Direction.REDEEM, Opportunity.Trade::direction)
          .returns(19.8, t -> Math.round(t.grossEdgePerUnit() * 100) / 100.0);
    }

    @Test
    void passesWhenTheFundSpreadStraddlesTheBasket() {
      // The fund's bid is below what the basket costs and its ask is above what the
      // basket fetches, so neither round trip makes money.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .quote("FAIRX", 19.98, 1000, 20.00, 1000)
              .etf("FAIRX", 10, "AAA", 2)
              .scanOne();

      assertSkipped(result, SkipReason.NO_EDGE);
    }
  }

  @Nested
  @DisplayName("sizing")
  class SizingCases {

    @Test
    void sizesToTheThinnestLegAndNamesIt() {
      // Creating needs 2 AAA and 5 BBB a unit. AAA supports 400 units, BBB only 40.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 800)
              .quote("BBB", 9.90, 1000, 10.00, 200)
              .quote("RICHX", 30.00, 100_000, 30.10, 100_000)
              .etf("RICHX", 10, "AAA", 2, "BBB", 5)
              .scanOne();

      assertTrade(result)
          .returns(40L, Opportunity.Trade::units)
          .returns("BBB", Opportunity.Trade::limitingSymbol);
    }

    @Test
    void theFundItselfCanBeTheBindingLeg() {
      // The fund's own bid supports only 3 creation units of 10 shares.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 100_000, 100.00, 100_000)
              .quote("RICHX", 30.00, 35, 30.10, 35)
              .etf("RICHX", 10, "AAA", 2)
              .scanOne();

      assertTrade(result)
          .returns(3L, Opportunity.Trade::units)
          .returns("RICHX", Opportunity.Trade::limitingSymbol);
    }

    @Test
    void reportsNoExecutableSizeWhenALegCannotFillOneUnit() {
      // A unit needs 200 AAA to create but only 150 are offered, and the fund's own
      // offer is too high for the other direction to be worth anything either.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 150)
              .quote("RICHX", 2100.00, 100_000, 2101.00, 100_000)
              .etf("RICHX", 10, "AAA", 200)
              .scanOne();

      assertSkipped(result, SkipReason.NO_EXECUTABLE_SIZE);
    }

    @Test
    void totalEdgeScalesWithTheExecutableSize() {
      Opportunity.Trade trade =
          (Opportunity.Trade)
              book()
                  .quote("AAA", 99.90, 1000, 100.00, 80)
                  .quote("RICHX", 25.00, 100_000, 25.10, 100_000)
                  .etf("RICHX", 10, "AAA", 2)
                  .scanOne();

      assertThat(trade.units()).isEqualTo(40);
      assertThat(trade.netEdgeTotal()).isEqualTo(trade.netEdgePerUnit() * 40);
    }
  }

  @Nested
  @DisplayName("fees")
  class FeeCases {

    @Test
    void theUnitFeeIsChargedPerCreationUnit() {
      Opportunity.Trade trade =
          (Opportunity.Trade)
              book()
                  .quote("AAA", 99.90, 1000, 100.00, 1000)
                  .quote("RICHX", 25.00, 1000, 25.10, 1000)
                  .etf("RICHX", 10, "AAA", 2)
                  .scanOne(QuotePolicy.PERMISSIVE, new FeeSchedule(0, 12), 0);

      assertThat(trade.grossEdgePerUnit()).isEqualTo(50.0);
      assertThat(trade.feesPerUnit()).isEqualTo(12.0);
      assertThat(trade.netEdgePerUnit()).isEqualTo(38.0);
    }

    @Test
    void commissionIsChargedOnBothLegs() {
      // 200.00 bought plus 250.00 sold is 450.00 turned over; 100 bps of that is 4.50.
      Opportunity.Trade trade =
          (Opportunity.Trade)
              book()
                  .quote("AAA", 99.90, 1000, 100.00, 1000)
                  .quote("RICHX", 25.00, 1000, 25.10, 1000)
                  .etf("RICHX", 10, "AAA", 2)
                  .scanOne(QuotePolicy.PERMISSIVE, new FeeSchedule(100, 0), 0);

      assertThat(trade.feesPerUnit()).isEqualTo(4.5);
    }

    @Test
    void anEdgeSmallerThanTheFeesIsNotATrade() {
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .quote("RICHX", 25.00, 1000, 25.10, 1000)
              .etf("RICHX", 10, "AAA", 2)
              .scanOne(QuotePolicy.PERMISSIVE, new FeeSchedule(0, 60), 0);

      assertSkipped(result, SkipReason.EDGE_BELOW_THRESHOLD);
    }

    @Test
    void anEdgeBelowTheMinimumIsNotWorthReporting() {
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .quote("RICHX", 25.00, 1000, 25.10, 1000)
              .etf("RICHX", 10, "AAA", 2)
              .scanOne(QuotePolicy.PERMISSIVE, FeeSchedule.FREE, 100_000);

      assertSkipped(result, SkipReason.EDGE_BELOW_THRESHOLD);
    }
  }

  @Nested
  @DisplayName("bad market data")
  class BadDataCases {

    @Test
    void refusesToTradeOnAStaleQuote() {
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000, Books.NOW.minusSeconds(60))
              .quote("RICHX", 25.00, 1000, 25.10, 1000)
              .etf("RICHX", 10, "AAA", 2)
              .scanOne(new QuotePolicy(Duration.ofSeconds(5)), FeeSchedule.FREE, 0);

      assertSkipped(result, SkipReason.STALE_QUOTE);
    }

    @Test
    void aStaleFundQuoteIsCaughtEvenWhenTheBasketIsFresh() {
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .quote("RICHX", 25.00, 1000, 25.10, 1000, Books.NOW.minusSeconds(60))
              .etf("RICHX", 10, "AAA", 2)
              .scanOne(new QuotePolicy(Duration.ofSeconds(5)), FeeSchedule.FREE, 0);

      assertSkipped(result, SkipReason.STALE_QUOTE);
    }

    @Test
    void refusesToTradeOnACrossedBook() {
      // A crossed constituent would otherwise show a large and entirely fictional edge.
      Opportunity result =
          book()
              .quote("AAA", 100.00, 1000, 99.00, 1000)
              .quote("RICHX", 25.00, 1000, 25.10, 1000)
              .etf("RICHX", 10, "AAA", 2)
              .scanOne();

      assertSkipped(result, SkipReason.CROSSED_QUOTE);
    }

    @Test
    void anOfferOnlyFundCanStillBeRedeemed() {
      // No bid on the fund rules out creating, but buying it and selling the basket is
      // still a complete round trip.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 1000, 100.00, 1000)
              .askOnly("CHEAPX", 18.00, 1000)
              .etf("CHEAPX", 10, "AAA", 2)
              .scanOne();

      assertTrade(result).returns(Direction.REDEEM, Opportunity.Trade::direction);
    }

    @Test
    void skipsWhenNeitherDirectionHasBothLegs() {
      // The fund has no bid, so it cannot be created; the constituent has no bid, so the
      // basket cannot be sold and it cannot be redeemed either.
      Opportunity result =
          book()
              .askOnly("AAA", 100.00, 1000)
              .askOnly("LOPSX", 18.00, 1000)
              .etf("LOPSX", 10, "AAA", 2)
              .scanOne();

      assertSkipped(result, SkipReason.ONE_SIDED_QUOTE);
    }

    @Test
    void skipsAFundThatIsNotQuoted() {
      Opportunity result =
          book().quote("AAA", 99.90, 1000, 100.00, 1000).etf("DARKX", 10, "AAA", 2).scanOne();

      assertSkipped(result, SkipReason.UNQUOTED_ETF);
    }

    @Test
    void skipsAConstituentThatIsNeitherQuotedNorAFund() {
      Opportunity result =
          book().quote("RICHX", 25.00, 1000, 25.10, 1000).etf("RICHX", 10, "GHOST", 2).scanOne();

      assertSkipped(result, SkipReason.UNPRICED_CONSTITUENT);
    }
  }

  @Nested
  @DisplayName("look-through")
  class LookThroughCases {

    @Test
    void anUnquotedSubFundIsValuedThroughItsOwnBasket() {
      // WRAPX holds 20 shares of unquoted SEEDX, which is 2 creation units of 10 shares,
      // each holding 3 AAA. Creating WRAPX therefore buys 6 AAA at 100.00 for 600.00 and
      // sells 10 WRAPX shares at 70.00 for 700.00.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 100_000, 100.00, 100_000)
              .quote("WRAPX", 70.00, 100_000, 70.10, 100_000)
              .etf("WRAPX", 10, "SEEDX", 20)
              .etf("SEEDX", 10, "AAA", 3)
              .scanOne();

      assertTrade(result)
          .returns(Direction.CREATE, Opportunity.Trade::direction)
          .returns(100.0, t -> t.grossEdgePerUnit());
    }

    @Test
    void aQuotedSubFundIsTradedDirectlyRatherThanLookedThrough() {
      // SUBX is quoted at 5.00, well away from the 100.00 its own basket implies. The
      // scanner buys SUBX at its quote, so one unit of WRAPX costs 20 x 5.01 = 100.20.
      Opportunity.Trade trade =
          (Opportunity.Trade)
              book()
                  .quote("AAA", 99.90, 100_000, 100.00, 100_000)
                  .quote("SUBX", 5.00, 100_000, 5.01, 100_000)
                  .quote("WRAPX", 70.00, 100_000, 70.10, 100_000)
                  .etf("WRAPX", 10, "SUBX", 20)
                  .etf("SUBX", 10, "AAA", 3)
                  .scanOne();

      assertThat(trade.grossEdgePerUnit()).isEqualTo(700.0 - 100.2);
    }

    @Test
    void skipsAPartUnitHoldingOfAnUnquotedSubFund() {
      // 15 shares of a fund whose creation unit is 10 shares cannot be assembled.
      Opportunity result =
          book()
              .quote("AAA", 99.90, 100_000, 100.00, 100_000)
              .quote("FRACX", 70.00, 100_000, 70.10, 100_000)
              .etf("FRACX", 10, "SEEDX", 15)
              .etf("SEEDX", 10, "AAA", 3)
              .scanOne();

      assertSkipped(result, SkipReason.INDIVISIBLE_LOOKTHROUGH);
    }
  }

  @Test
  void reportsOneVerdictPerFundInScanOrder() {
    var results =
        new ArbitrageScanner(QuotePolicy.PERMISSIVE, FeeSchedule.FREE, 0)
            .scan(
                book()
                    .quote("AAA", 99.90, 1000, 100.00, 1000)
                    .quote("RICHX", 25.00, 1000, 25.10, 1000)
                    .quote("FAIRX", 19.98, 1000, 20.00, 1000)
                    .etf("RICHX", 10, "AAA", 2)
                    .etf("FAIRX", 10, "AAA", 2)
                    .universe());

    assertThat(results).extracting(Opportunity::etf).containsExactly("RICHX", "FAIRX");
  }
}
