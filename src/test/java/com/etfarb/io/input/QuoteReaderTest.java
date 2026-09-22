package com.etfarb.io.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etfarb.domain.model.Level;
import com.etfarb.domain.model.Quote;
import com.etfarb.error.MarketDataException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuoteReaderTest {

  private static final String HEADER = "symbol,bid,bid_size,ask,ask_size,as_of\n";
  private static final String STAMP = "2026-09-21T14:30:00Z";

  @TempDir Path dir;

  private Map<String, Quote> read(String body) throws IOException {
    Path file = dir.resolve("quotes.csv");
    Files.writeString(file, HEADER + body);
    return new QuoteReader().read(file);
  }

  @Test
  void readsATwoSidedQuote() throws IOException {
    Quote quote = read("AAA,10.00,300,10.05,250," + STAMP + "\n").get("AAA");

    assertThat(quote.bid()).contains(new Level(10.00, 300));
    assertThat(quote.ask()).contains(new Level(10.05, 250));
    assertThat(quote.asOf()).isEqualTo(Instant.parse(STAMP));
  }

  @Test
  void anEmptySideMeansThereIsNothingResting() throws IOException {
    Quote quote = read("AAA,,,10.05,250," + STAMP + "\n").get("AAA");

    assertThat(quote.bid()).isEmpty();
    assertThat(quote.ask()).contains(new Level(10.05, 250));
  }

  @Test
  void skipsBlankLinesAndComments() throws IOException {
    assertThat(read("\n# a comment\nAAA,10.00,300,10.05,250," + STAMP + "\n")).hasSize(1);
  }

  @Test
  void toleratesColumnsInAnyOrder() throws IOException {
    Path file = dir.resolve("reordered.csv");
    Files.writeString(
        file, "as_of,symbol,ask,ask_size,bid,bid_size\n" + STAMP + ",AAA,10.05,250,10.00,300\n");

    assertThat(new QuoteReader().read(file).get("AAA").bid()).contains(new Level(10.00, 300));
  }

  @Test
  void rejectsAPriceWithoutItsSize() {
    assertThatThrownBy(() -> read("AAA,10.00,,10.05,250," + STAMP + "\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("quotes.csv:2")
        .hasMessageContaining("must be given together");
  }

  @Test
  void rejectsAQuoteWithNeitherSide() {
    assertThatThrownBy(() -> read("AAA,,,,," + STAMP + "\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("neither a bid nor an ask");
  }

  @Test
  void rejectsANegativePrice() {
    assertThatThrownBy(() -> read("AAA,-1.00,300,10.05,250," + STAMP + "\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("price must be finite and positive");
  }

  @Test
  void rejectsAnUnparseableTimestamp() {
    assertThatThrownBy(() -> read("AAA,10.00,300,10.05,250,yesterday\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  void rejectsADuplicateSymbol() {
    String row = "AAA,10.00,300,10.05,250," + STAMP + "\n";
    assertThatThrownBy(() -> read(row + row))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("quotes.csv:3")
        .hasMessageContaining("duplicate quote");
  }

  @Test
  void rejectsAMissingColumn() {
    Path file = dir.resolve("short.csv");
    assertThatThrownBy(
            () -> {
              Files.writeString(file, "symbol,bid,bid_size\nAAA,10.00,300\n");
              new QuoteReader().read(file);
            })
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("missing column 'ask'");
  }

  @Test
  void rejectsAFileWithNoHeader() {
    Path file = dir.resolve("empty.csv");
    assertThatThrownBy(
            () -> {
              Files.writeString(file, "# nothing but a comment\n");
              new QuoteReader().read(file);
            })
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("no header row");
  }
}
