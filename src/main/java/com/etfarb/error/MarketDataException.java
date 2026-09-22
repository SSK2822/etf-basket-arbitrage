package com.etfarb.error;

/**
 * Malformed or inconsistent market data, carrying the source that produced it and the 1-based line
 * number, so an operator can go straight to the offending row.
 */
public final class MarketDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String source;
  private final int line;

  public MarketDataException(String source, int line, String message) {
    super("%s:%d %s".formatted(source, line, message));
    this.source = source;
    this.line = line;
  }

  public String source() {
    return source;
  }

  public int line() {
    return line;
  }
}
