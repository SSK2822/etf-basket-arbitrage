package com.etfarb.cli;

import com.etfarb.domain.pricing.FeeSchedule;
import com.etfarb.domain.pricing.QuotePolicy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;

/**
 * Parsed command line.
 *
 * <p>The three paths are required; the trading parameters have defaults chosen to be conservative
 * rather than flattering, so a run with no tuning does not overstate the opportunity.
 */
public record CliOptions(
    Path quotes, Path baskets, Path output, FeeSchedule fees, QuotePolicy policy, double minEdge) {

  public static final String USAGE =
      """
      usage: etf-arb --quotes <file> --baskets <file> --out <file> [options]

        --quotes <file>       CSV of top-of-book quotes
        --baskets <file>      CSV of creation baskets
        --out <file>          CSV report to write

        --fee-bps <n>         commission in basis points of traded notional (default 0.5)
        --unit-fee <n>        flat fee per creation unit (default 500)
        --max-quote-age <n>   seconds a quote stays tradable (default 5)
        --min-edge <n>        smallest net edge worth reporting (default 0.01)
      """;

  /** Thrown for anything the user can fix by retyping the command. */
  public static final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  public static CliOptions parse(String[] args) {
    Path quotes = null;
    Path baskets = null;
    Path output = null;
    double feeBps = 0.5;
    double unitFee = 500;
    long maxAgeSeconds = 5;
    double minEdge = 0.01;

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      switch (flag) {
        case "--quotes" -> quotes = path(value(args, ++i, flag));
        case "--baskets" -> baskets = path(value(args, ++i, flag));
        case "--out" -> output = path(value(args, ++i, flag));
        case "--fee-bps" -> feeBps = number(value(args, ++i, flag), flag);
        case "--unit-fee" -> unitFee = number(value(args, ++i, flag), flag);
        case "--max-quote-age" -> maxAgeSeconds = (long) number(value(args, ++i, flag), flag);
        case "--min-edge" -> minEdge = number(value(args, ++i, flag), flag);
        default -> throw new UsageException("unknown option '" + flag + "'");
      }
    }

    require(quotes, "--quotes");
    require(baskets, "--baskets");
    require(output, "--out");

    try {
      return new CliOptions(
          quotes,
          baskets,
          output,
          new FeeSchedule(feeBps, unitFee),
          new QuotePolicy(Duration.ofSeconds(maxAgeSeconds)),
          minEdge);
    } catch (IllegalArgumentException e) {
      throw new UsageException(e.getMessage());
    }
  }

  private static String value(String[] args, int index, String flag) {
    if (index >= args.length) {
      throw new UsageException("option '" + flag + "' needs a value");
    }
    return args[index];
  }

  private static Path path(String value) {
    return Paths.get(value);
  }

  private static double number(String value, String flag) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new UsageException(
          "option '%s' needs a number, got '%s'".formatted(flag, value.toLowerCase(Locale.ROOT)));
    }
  }

  private static void require(Path path, String flag) {
    if (path == null) {
      throw new UsageException("missing required option '" + flag + "'");
    }
  }
}
