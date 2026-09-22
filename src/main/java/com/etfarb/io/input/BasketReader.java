package com.etfarb.io.input;

import com.etfarb.domain.model.Constituent;
import com.etfarb.domain.model.Etf;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a creation baskets file, one row per constituent.
 *
 * <pre>
 *   etf,unit_size,constituent,quantity
 *   TECHX,1000,AAPL,120
 *   TECHX,1000,MSFT,95
 * </pre>
 *
 * <p>{@code unit_size} is repeated on every row of a fund and must agree across them. ETFs are
 * returned in the order they first appear, which is the order the scanner reports them in.
 *
 * <p>The reader also rejects circular baskets, so the look-through pass can recurse without a depth
 * guard.
 */
public final class BasketReader {

  public Map<String, Etf> read(Path path) throws IOException {
    Map<String, Long> unitSizes = new LinkedHashMap<>();
    Map<String, List<Constituent>> baskets = new LinkedHashMap<>();
    Map<String, CsvRow> firstRow = new HashMap<>();

    for (CsvRow row : CsvReader.read(path)) {
      String symbol = row.requireText("etf");
      long unitSize = row.requireLong("unit_size");

      Long known = unitSizes.putIfAbsent(symbol, unitSize);
      if (known != null && known != unitSize) {
        throw row.fail(
            "etf '%s' was declared with unit size %d and again with %d"
                .formatted(symbol, known, unitSize));
      }
      firstRow.putIfAbsent(symbol, row);

      Constituent constituent;
      try {
        constituent = new Constituent(row.requireText("constituent"), row.requireLong("quantity"));
      } catch (IllegalArgumentException e) {
        throw row.fail(e.getMessage());
      }
      baskets.computeIfAbsent(symbol, key -> new ArrayList<>()).add(constituent);
    }

    Map<String, Etf> etfs = new LinkedHashMap<>();
    baskets.forEach(
        (symbol, constituents) -> {
          try {
            etfs.put(symbol, new Etf(symbol, unitSizes.get(symbol), constituents));
          } catch (IllegalArgumentException e) {
            throw firstRow.get(symbol).fail(e.getMessage());
          }
        });

    rejectCycles(etfs, firstRow);
    return etfs;
  }

  /** Depth-first search over basket references, reporting the fund that closes the cycle. */
  private static void rejectCycles(Map<String, Etf> etfs, Map<String, CsvRow> firstRow) {
    Set<String> settled = new HashSet<>();
    for (String root : etfs.keySet()) {
      if (settled.contains(root)) {
        continue;
      }
      Set<String> onPath = new HashSet<>();
      Deque<String> stack = new ArrayDeque<>();
      visit(root, etfs, settled, onPath, stack, firstRow);
    }
  }

  private static void visit(
      String symbol,
      Map<String, Etf> etfs,
      Set<String> settled,
      Set<String> onPath,
      Deque<String> stack,
      Map<String, CsvRow> firstRow) {

    if (settled.contains(symbol)) {
      return;
    }
    if (!onPath.add(symbol)) {
      String cycle = String.join(" -> ", stack.reversed()) + " -> " + symbol;
      throw firstRow.get(symbol).fail("circular basket: " + cycle);
    }
    stack.push(symbol);

    Etf etf = etfs.get(symbol);
    if (etf != null) {
      for (Constituent constituent : etf.basket()) {
        if (etfs.containsKey(constituent.symbol())) {
          visit(constituent.symbol(), etfs, settled, onPath, stack, firstRow);
        }
      }
    }

    stack.pop();
    onPath.remove(symbol);
    settled.add(symbol);
  }
}
