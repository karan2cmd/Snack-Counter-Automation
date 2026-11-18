package com.framsikt.snack.adapter;

import com.framsikt.snack.port.FxRateProvider;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configurable FX rates expressed against one base currency (e.g. NOK). In production this would be
 * the finance team's monthly rate table or a central-bank feed.
 */
public final class FixedFxRateProvider implements FxRateProvider {

    private final Currency base;
    private final Map<Currency, BigDecimal> unitsOfBasePerUnit = new HashMap<>();

    public FixedFxRateProvider(Currency base) {
        this.base = Objects.requireNonNull(base, "base");
        unitsOfBasePerUnit.put(base, BigDecimal.ONE);
    }

    /** e.g. {@code withRate(INR, "0.125")} means 1 INR = 0.125 NOK when base is NOK. */
    public FixedFxRateProvider withRate(Currency currency, String unitsOfBase) {
        BigDecimal rate = new BigDecimal(unitsOfBase);
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("Rate must be positive");
        }
        unitsOfBasePerUnit.put(currency, rate);
        return this;
    }

    @Override
    public BigDecimal rate(Currency from, Currency to) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        return toBase(from).divide(toBase(to), MathContext.DECIMAL64);
    }

    private BigDecimal toBase(Currency c) {
        BigDecimal r = unitsOfBasePerUnit.get(c);
        if (r == null) {
            throw new IllegalArgumentException("No FX rate configured for " + c + " against " + base);
        }
        return r;
    }
}
