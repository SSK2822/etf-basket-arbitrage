package com.etfarb.cli;

import com.etfarb.domain.model.Universe;
import com.etfarb.domain.pricing.ArbitrageScanner;
import com.etfarb.domain.pricing.Opportunity;
import com.etfarb.error.MarketDataException;
import com.etfarb.io.input.UniverseLoader;
import com.etfarb.io.output.OpportunityWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

/**
 * The application, wired by hand and given its streams, so the whole pipeline can be driven from a
 * test without touching {@link System#out}.
 *
 * <p>This is the only layer that reads or writes files; everything below it is pure.
 */
public final class ScannerApp {

  private final UniverseLoader loader = new UniverseLoader();
  private final OpportunityWriter writer = new OpportunityWriter();
  private final PrintStream err;

  public ScannerApp(PrintStream err) {
    this.err = err;
  }

  public ExitCode run(String[] args) {
    CliOptions options;
    try {
      options = CliOptions.parse(args);
    } catch (CliOptions.UsageException e) {
      err.println("error: " + e.getMessage());
      err.print(CliOptions.USAGE);
      return ExitCode.USAGE;
    }

    try {
      Universe universe = loader.load(options.quotes(), options.baskets());
      List<Opportunity> opportunities =
          new ArbitrageScanner(options.policy(), options.fees(), options.minEdge()).scan(universe);
      write(options.output(), writer.render(opportunities));
      return ExitCode.OK;
    } catch (MarketDataException e) {
      err.println("invalid input: " + e.getMessage());
      return ExitCode.INVALID_INPUT;
    } catch (NoSuchFileException e) {
      err.println("no such file: " + e.getFile());
      return ExitCode.IO_FAILURE;
    } catch (IOException | UncheckedIOException e) {
      err.println("io failure: " + e.getMessage());
      return ExitCode.IO_FAILURE;
    }
  }

  private static void write(Path output, String report) throws IOException {
    Path parent = output.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(output, report, StandardCharsets.UTF_8);
  }
}
