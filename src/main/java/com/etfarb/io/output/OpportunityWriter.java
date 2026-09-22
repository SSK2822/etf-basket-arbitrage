package com.etfarb.io.output;

import com.etfarb.domain.pricing.Opportunity;
import java.util.List;
import java.util.Locale;

/**
 * Renders the scan as CSV, one row per ETF in scan order.
 *
 * <pre>
 *   etf,action,units,gross_edge,fees,net_edge,limiting_symbol,reason
 *   TECHX,CREATE,12,1450.00,240.00,1210.00,MSFT,
 *   RDTH,NONE,0,0.00,0.00,0.00,,NO_EDGE
 * </pre>
 *
 * <p>Money columns are per creation unit except {@code net_edge}, which is the whole trade. Amounts
 * use two decimals under {@link Locale#ROOT}, so output never shifts with the host locale.
 */
public final class OpportunityWriter {

  public static final String HEADER =
      "etf,action,units,gross_edge,fees,net_edge,limiting_symbol,reason";

  public String render(List<Opportunity> opportunities) {
    StringBuilder report = new StringBuilder(HEADER).append('\n');
    for (Opportunity opportunity : opportunities) {
      report.append(row(opportunity)).append('\n');
    }
    return report.toString();
  }

  private static String row(Opportunity opportunity) {
    return switch (opportunity) {
      case Opportunity.Trade trade ->
          "%s,%s,%d,%s,%s,%s,%s,"
              .formatted(
                  trade.etf(),
                  trade.direction(),
                  trade.units(),
                  money(trade.grossEdgePerUnit()),
                  money(trade.feesPerUnit()),
                  money(trade.netEdgeTotal()),
                  trade.limitingSymbol());
      case Opportunity.Skip skip ->
          "%s,NONE,0,0.00,0.00,0.00,,%s".formatted(skip.etf(), skip.reason());
    };
  }

  private static String money(double amount) {
    return String.format(Locale.ROOT, "%.2f", amount);
  }
}
