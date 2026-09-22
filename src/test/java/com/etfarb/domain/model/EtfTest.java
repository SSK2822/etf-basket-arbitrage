package com.etfarb.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EtfTest {

  @Test
  void keepsBasketOrderAndIsImmutable() {
    Etf etf = new Etf("AAAX", 100, List.of(new Constituent("AAA", 10), new Constituent("BBB", 20)));
    assertThat(etf.basket()).extracting(Constituent::symbol).containsExactly("AAA", "BBB");
    assertThatThrownBy(() -> etf.basket().add(new Constituent("CCC", 1)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsAnEmptyBasket() {
    assertThatThrownBy(() -> new Etf("AAAX", 100, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty creation basket");
  }

  @Test
  void rejectsARepeatedConstituent() {
    assertThatThrownBy(
            () ->
                new Etf("AAAX", 100, List.of(new Constituent("AAA", 1), new Constituent("AAA", 2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repeats a constituent");
  }

  @Test
  void rejectsAFundThatHoldsItself() {
    assertThatThrownBy(() -> new Etf("AAAX", 100, List.of(new Constituent("AAAX", 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lists itself");
  }

  @Test
  void rejectsANonPositiveUnitSize() {
    assertThatThrownBy(() -> new Etf("AAAX", 0, List.of(new Constituent("AAA", 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANonPositiveConstituentQuantity() {
    assertThatThrownBy(() -> new Constituent("AAA", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
