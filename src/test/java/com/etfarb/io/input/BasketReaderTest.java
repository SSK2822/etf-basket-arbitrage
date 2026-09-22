package com.etfarb.io.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etfarb.domain.model.Constituent;
import com.etfarb.domain.model.Etf;
import com.etfarb.error.MarketDataException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BasketReaderTest {

  private static final String HEADER = "etf,unit_size,constituent,quantity\n";

  @TempDir Path dir;

  private Map<String, Etf> read(String body) throws IOException {
    Path file = dir.resolve("baskets.csv");
    Files.writeString(file, HEADER + body);
    return new BasketReader().read(file);
  }

  @Test
  void collectsRowsIntoOneFundPerSymbol() throws IOException {
    Etf etf = read("AAAX,100,AAA,3\nAAAX,100,BBB,4\n").get("AAAX");

    assertThat(etf.unitSize()).isEqualTo(100);
    assertThat(etf.basket()).containsExactly(new Constituent("AAA", 3), new Constituent("BBB", 4));
  }

  @Test
  void keepsFundsInTheOrderTheyFirstAppear() throws IOException {
    Map<String, Etf> etfs = read("BBBX,100,BBB,1\nAAAX,100,AAA,1\nBBBX,100,CCC,1\n");

    assertThat(etfs.keySet()).containsExactly("BBBX", "AAAX");
  }

  @Test
  void allowsAFundToHoldAnotherFund() throws IOException {
    Map<String, Etf> etfs = read("OUTX,100,INNX,200\nOUTX,100,AAA,1\nINNX,100,BBB,5\n");

    assertThat(etfs.get("OUTX").basket()).contains(new Constituent("INNX", 200));
  }

  @Test
  void rejectsAUnitSizeThatDisagreesBetweenRows() {
    assertThatThrownBy(() -> read("AAAX,100,AAA,3\nAAAX,200,BBB,4\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("baskets.csv:3")
        .hasMessageContaining("declared with unit size 100 and again with 200");
  }

  @Test
  void rejectsARepeatedConstituent() {
    assertThatThrownBy(() -> read("AAAX,100,AAA,3\nAAAX,100,AAA,4\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("repeats a constituent");
  }

  @Test
  void rejectsANonPositiveQuantity() {
    assertThatThrownBy(() -> read("AAAX,100,AAA,0\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("quantity must be positive");
  }

  @Test
  void rejectsAFundThatHoldsItself() {
    assertThatThrownBy(() -> read("AAAX,100,AAAX,1\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("lists itself");
  }

  @Test
  void rejectsATwoFundCycle() {
    assertThatThrownBy(() -> read("AAAX,100,BBBX,100\nBBBX,100,AAAX,100\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("circular basket");
  }

  @Test
  void rejectsALongerCycle() {
    assertThatThrownBy(() -> read("AAAX,100,BBBX,100\nBBBX,100,CCCX,100\nCCCX,100,AAAX,100\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("circular basket");
  }

  @Test
  void aSharedSubFundIsNotACycle() throws IOException {
    // Both funds hold SUBX. A naive visited-set check would call the second one circular.
    Map<String, Etf> etfs = read("AAAX,100,SUBX,100\nBBBX,100,SUBX,100\nSUBX,100,AAA,1\n");

    assertThat(etfs).containsKeys("AAAX", "BBBX", "SUBX");
  }

  @Test
  void rejectsANonNumericQuantity() {
    assertThatThrownBy(() -> read("AAAX,100,AAA,many\n"))
        .isInstanceOf(MarketDataException.class)
        .hasMessageContaining("not an integer");
  }
}
