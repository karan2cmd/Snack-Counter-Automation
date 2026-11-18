package com.framsikt.snack.port;

import java.math.BigDecimal;
import java.util.Currency;

/** Source of exchange rates for the combined management report. */
public interface FxRateProvider {

    /** @return how many units of {@code to} one unit of {@code from} is worth today */
    BigDecimal rate(Currency from, Currency to);
}
