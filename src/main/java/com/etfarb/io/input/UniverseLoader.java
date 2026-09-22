package com.etfarb.io.input;

import com.etfarb.domain.model.Etf;
import com.etfarb.domain.model.Quote;
import com.etfarb.domain.model.Universe;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Loads a quotes file and a baskets file into one {@link Universe}. */
public final class UniverseLoader {

  private final QuoteReader quoteReader = new QuoteReader();
  private final BasketReader basketReader = new BasketReader();

  public Universe load(Path quotesPath, Path basketsPath) throws IOException {
    Map<String, Quote> quotes = quoteReader.read(quotesPath);
    Map<String, Etf> etfs = basketReader.read(basketsPath);
    List<String> scanOrder = List.copyOf(etfs.keySet());
    return new Universe(quotes, etfs, scanOrder, asOf(quotes));
  }

  /**
   * The snapshot is judged against its own newest quote rather than the wall clock, so replaying a
   * captured file gives the same answer tomorrow as it did when it was captured.
   */
  private static Instant asOf(Map<String, Quote> quotes) {
    return quotes.values().stream().map(Quote::asOf).max(Instant::compareTo).orElse(Instant.EPOCH);
  }
}
