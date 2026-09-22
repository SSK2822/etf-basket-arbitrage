package com.etfarb.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScannerAppTest {

  private static final Path SAMPLE = Path.of("src/test/resources/sample");
  private static final String STAMP = "2026-09-21T14:30:00Z";

  @TempDir Path dir;

  private ByteArrayOutputStream errBuffer;
  private ScannerApp app;

  @BeforeEach
  void setUp() {
    errBuffer = new ByteArrayOutputStream();
    app = new ScannerApp(new PrintStream(errBuffer, true, StandardCharsets.UTF_8));
  }

  private String err() {
    return errBuffer.toString(StandardCharsets.UTF_8);
  }

  private Path write(String name, String content) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  @Nested
  class SampleSnapshot {

    @Test
    void reproducesTheExpectedReportForTheBundledSample() throws IOException {
      Path out = dir.resolve("report.csv");

      ExitCode code =
          app.run(
              new String[] {
                "--quotes", SAMPLE.resolve("quotes.csv").toString(),
                "--baskets", SAMPLE.resolve("baskets.csv").toString(),
                "--out", out.toString()
              });

      assertThat(code).isEqualTo(ExitCode.OK);
      assertThat(Files.readString(out))
          .isEqualTo(Files.readString(SAMPLE.resolve("expected-report.csv")));
    }
  }

  @Nested
  class Wiring {

    @Test
    void createsTheOutputDirectoryIfItIsMissing() throws IOException {
      Path out = dir.resolve("nested/deeper/report.csv");

      ExitCode code =
          app.run(
              new String[] {
                "--quotes", SAMPLE.resolve("quotes.csv").toString(),
                "--baskets", SAMPLE.resolve("baskets.csv").toString(),
                "--out", out.toString()
              });

      assertThat(code).isEqualTo(ExitCode.OK);
      assertThat(out).exists();
    }

    @Test
    void feeSettingsChangeWhatIsReported() throws IOException {
      Path out = dir.resolve("report.csv");
      String[] base = {
        "--quotes", SAMPLE.resolve("quotes.csv").toString(),
        "--baskets", SAMPLE.resolve("baskets.csv").toString(),
        "--out", out.toString(),
        "--unit-fee", "1000000"
      };

      assertThat(app.run(base)).isEqualTo(ExitCode.OK);
      // With a prohibitive unit fee nothing survives, so every row is a pass.
      assertThat(Files.readString(out).lines().skip(1)).allMatch(line -> line.contains(",NONE,"));
    }
  }

  @Nested
  class Failures {

    @Test
    void reportsAUsageErrorForAMissingOption() {
      assertThat(app.run(new String[] {"--quotes", "q.csv"})).isEqualTo(ExitCode.USAGE);
      assertThat(err()).contains("missing required option '--baskets'").contains("usage:");
    }

    @Test
    void reportsAUsageErrorForAnUnknownOption() {
      assertThat(app.run(new String[] {"--wat"})).isEqualTo(ExitCode.USAGE);
      assertThat(err()).contains("unknown option '--wat'");
    }

    @Test
    void reportsAUsageErrorForAFlagWithNoValue() {
      assertThat(app.run(new String[] {"--quotes"})).isEqualTo(ExitCode.USAGE);
      assertThat(err()).contains("needs a value");
    }

    @Test
    void reportsAUsageErrorForANonNumericSetting() {
      assertThat(app.run(new String[] {"--fee-bps", "lots"})).isEqualTo(ExitCode.USAGE);
      assertThat(err()).contains("needs a number");
    }

    @Test
    void reportsAnIoFailureForAMissingInputFile() {
      ExitCode code =
          app.run(
              new String[] {
                "--quotes", dir.resolve("absent.csv").toString(),
                "--baskets", SAMPLE.resolve("baskets.csv").toString(),
                "--out", dir.resolve("report.csv").toString()
              });

      assertThat(code).isEqualTo(ExitCode.IO_FAILURE);
      assertThat(err()).contains("no such file");
    }

    @Test
    void reportsInvalidInputWithTheOffendingLine() throws IOException {
      Path quotes =
          write(
              "quotes.csv",
              "symbol,bid,bid_size,ask,ask_size,as_of\nAAA,x,300,1,1," + STAMP + "\n");
      Path baskets = write("baskets.csv", "etf,unit_size,constituent,quantity\nAAAX,100,AAA,1\n");

      ExitCode code =
          app.run(
              new String[] {
                "--quotes", quotes.toString(),
                "--baskets", baskets.toString(),
                "--out", dir.resolve("report.csv").toString()
              });

      assertThat(code).isEqualTo(ExitCode.INVALID_INPUT);
      assertThat(err()).contains("invalid input").contains("quotes.csv:2");
    }

    @Test
    void exitCodesAreDistinct() {
      assertThat(java.util.Arrays.stream(ExitCode.values()).map(ExitCode::value).distinct())
          .hasSize(ExitCode.values().length);
    }
  }
}
