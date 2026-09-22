package com.etfarb.io.input;

import com.etfarb.domain.model.Level;
import com.etfarb.domain.model.Quote;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a quotes file.
 *
 * <pre>
 *   symbol,bid,bid_size,ask,ask_size,as_of
 *   XLK,262.10,3000,262.14,2500,2026-09-21T14:30:00Z
 *   RDTH,,,41.88,400,2026-09-21T14:30:00Z
 * </pre>
 *
 * <p>Leaving both the price and the size of a side empty means that side of the book is empty,
 * which the scanner treats as a one-sided market rather than as a zero price.
 */
public final class QuoteReader {

  public Map<String, Quote> read(Path path) throws IOException {
    Map<String, Quote> quotes = new LinkedHashMap<>();
    for (CsvRow row : CsvReader.read(path)) {
      Quote quote = quoteOf(row);
      if (quotes.put(quote.symbol(), quote) != null) {
        throw row.fail("duplicate quote for symbol '" + quote.symbol() + "'");
      }
    }
    return quotes;
  }

  private static Quote quoteOf(CsvRow row) {
    String symbol = row.requireText("symbol");
    Optional<Level> bid = level(row, "bid", "bid_size");
    Optional<Level> ask = level(row, "ask", "ask_size");
    if (bid.isEmpty() && ask.isEmpty()) {
      throw row.fail("quote for '" + symbol + "' has neither a bid nor an ask");
    }
    return new Quote(symbol, bid, ask, timestamp(row));
  }

  private static Optional<Level> level(CsvRow row, String priceColumn, String sizeColumn) {
    boolean noPrice = row.isBlank(priceColumn);
    boolean noSize = row.isBlank(sizeColumn);
    if (noPrice && noSize) {
      return Optional.empty();
    }
    if (noPrice || noSize) {
      throw row.fail(
          "'%s' and '%s' must be given together or left empty together"
              .formatted(priceColumn, sizeColumn));
    }
    try {
      return Optional.of(new Level(row.requireDouble(priceColumn), row.requireLong(sizeColumn)));
    } catch (IllegalArgumentException e) {
      throw row.fail(e.getMessage());
    }
  }

  private static Instant timestamp(CsvRow row) {
    String value = row.requireText("as_of");
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw row.fail("'as_of' is not an ISO-8601 instant: '" + value + "'");
    }
  }
}
