package com.etfarb.io.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.etfarb.domain.pricing.Direction;
import com.etfarb.domain.pricing.Opportunity;
import com.etfarb.domain.pricing.SkipReason;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpportunityWriterTest {

  private final OpportunityWriter writer = new OpportunityWriter();
  private final Locale original = Locale.getDefault();

  @AfterEach
  void restoreLocale() {
    Locale.setDefault(original);
  }

  @Test
  void writesAHeaderEvenWithNothingToReport() {
    assertThat(writer.render(List.of())).isEqualTo(OpportunityWriter.HEADER + "\n");
  }

  @Test
  void rendersATradeWithItsTotalEdge() {
    Opportunity.Trade trade =
        new Opportunity.Trade("AAAX", Direction.CREATE, 12, 150.0, 25.0, "BBB");

    assertThat(writer.render(List.of(trade)))
        .isEqualTo(OpportunityWriter.HEADER + "\nAAAX,CREATE,12,150.00,25.00,1500.00,BBB,\n");
  }

  @Test
  void rendersASkipWithItsReason() {
    Opportunity.Skip skip = new Opportunity.Skip("AAAX", SkipReason.STALE_QUOTE);

    assertThat(writer.render(List.of(skip)))
        .isEqualTo(OpportunityWriter.HEADER + "\nAAAX,NONE,0,0.00,0.00,0.00,,STALE_QUOTE\n");
  }

  @Test
  void preservesScanOrder() {
    String report =
        writer.render(
            List.of(
                new Opportunity.Skip("ONEX", SkipReason.NO_EDGE),
                new Opportunity.Trade("TWOX", Direction.REDEEM, 1, 10.0, 1.0, "AAA"),
                new Opportunity.Skip("SIXX", SkipReason.NO_EDGE)));

    assertThat(report.lines().skip(1).map(line -> line.split(",")[0]))
        .containsExactly("ONEX", "TWOX", "SIXX");
  }

  @Test
  void outputDoesNotFollowTheHostLocale() {
    // A locale that writes decimals with a comma would otherwise corrupt every CSV row.
    Locale.setDefault(Locale.GERMANY);
    Opportunity.Trade trade =
        new Opportunity.Trade("AAAX", Direction.CREATE, 2, 150.5, 25.25, "BBB");

    assertThat(writer.render(List.of(trade))).contains("150.50,25.25,250.50");
  }
}
