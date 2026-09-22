package com.etfarb.io.input;

import com.etfarb.error.MarketDataException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deliberately small CSV reader: a header row of column names, then comma separated rows. Blank
 * lines and {@code #} comments are skipped, and quoting is not supported because market data of
 * this shape has no reason to need it.
 */
final class CsvReader {

  private CsvReader() {}

  static List<CsvRow> read(Path path) throws IOException {
    String source = path.getFileName().toString();
    List<CsvRow> rows = new ArrayList<>();
    Map<String, Integer> header = null;
    int lineNumber = 0;

    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        List<String> fields = split(line);
        if (header == null) {
          header = headerOf(source, lineNumber, fields);
          continue;
        }
        rows.add(new CsvRow(source, lineNumber, header, fields));
      }
    }

    if (header == null) {
      throw new MarketDataException(source, 1, "file has no header row");
    }
    return rows;
  }

  private static Map<String, Integer> headerOf(String source, int line, List<String> fields) {
    Map<String, Integer> header = new HashMap<>();
    for (int i = 0; i < fields.size(); i++) {
      String name = fields.get(i).trim().toLowerCase(java.util.Locale.ROOT);
      if (name.isEmpty()) {
        throw new MarketDataException(source, line, "header column " + (i + 1) + " has no name");
      }
      if (header.put(name, i) != null) {
        throw new MarketDataException(source, line, "duplicate header column '" + name + "'");
      }
    }
    return Map.copyOf(header);
  }

  private static List<String> split(String line) {
    return List.of(line.split(",", -1));
  }
}
