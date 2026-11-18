package com.framsikt.snack.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void adds_and_subtracts_in_same_currency() {
        Money total = Money.of(1200, "NOK").plus(Money.of("300.50", "NOK")).minus(Money.of(500, "NOK"));
        assertThat(total).isEqualTo(Money.of("1000.50", "NOK"));
    }

    @Test
    void refuses_to_mix_currencies() {
        assertThatThrownBy(() -> Money.of(100, "NOK").plus(Money.of(100, "INR")))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> Money.of(100, "NOK").isGreaterThan(Money.of(100, "INR")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void equality_ignores_input_scale() {
        assertThat(Money.of("10", "INR")).isEqualTo(Money.of("10.00", "INR"));
    }

    @Test
    void converts_explicitly_with_bankers_rounding() {
        Money inr = Money.of("1001", "INR");
        Money nok = inr.convertTo(Currency.getInstance("NOK"), new BigDecimal("0.125"));
        assertThat(nok).isEqualTo(Money.of("125.12", "NOK")); // 125.125 -> 125.12 (HALF_EVEN)
    }
}
