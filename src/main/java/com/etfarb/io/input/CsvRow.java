package com.etfarb.io.input;

import com.etfarb.error.MarketDataException;
import java.util.List;
import java.util.Map;

/** One parsed CSV row, with typed accessors that fail with the row's source and line number. */
record CsvRow(String source, int line, Map<String, Integer> header, List<String> fields) {

  String text(String column) {
    Integer index = header.get(column);
    if (index == null) {
      throw new MarketDataException(source, line, "missing column '" + column + "'");
    }
    if (index >= fields.size()) {
      throw new MarketDataException(source, line, "row is short of column '" + column + "'");
    }
    return fields.get(index).trim();
  }

  /** True when the column is present but empty, which is how an absent quote side is written. */
  boolean isBlank(String column) {
    return text(column).isEmpty();
  }

  String requireText(String column) {
    String value = text(column);
    if (value.isEmpty()) {
      throw new MarketDataException(source, line, "column '" + column + "' must not be empty");
    }
    return value;
  }

  double requireDouble(String column) {
    String value = requireText(column);
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new MarketDataException(
          source, line, "column '" + column + "' is not a number: '" + value + "'");
    }
  }

  long requireLong(String column) {
    String value = requireText(column);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new MarketDataException(
          source, line, "column '" + column + "' is not an integer: '" + value + "'");
    }
  }

  MarketDataException fail(String message) {
    return new MarketDataException(source, line, message);
  }
}
