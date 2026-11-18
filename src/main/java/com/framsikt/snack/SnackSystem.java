package com.framsikt.snack;

import com.framsikt.snack.adapter.FakeChatNotifier;
import com.framsikt.snack.adapter.FakeHrSystem;
import com.framsikt.snack.adapter.FixedFxRateProvider;
import com.framsikt.snack.adapter.InMemoryAvailability;
import com.framsikt.snack.adapter.InMemoryRefillRepository;
import com.framsikt.snack.adapter.InMemoryVolunteerDirectory;
import com.framsikt.snack.adapter.MutableClock;
import com.framsikt.snack.service.BudgetService;
import com.framsikt.snack.service.RefillService;
import com.framsikt.snack.service.ReportService;
import com.framsikt.snack.service.SnackConfig;
import com.framsikt.snack.service.VolunteerSelector;

import java.time.Instant;
import java.util.Currency;

/**
 * Composition root: wires the services to the simulated adapters. In production only this class
 * changes (real chat bot, real HR API client, database repository, calendar integration).
 */
public final class SnackSystem {

    public final SnackConfig config;
    public final MutableClock clock;
    public final InMemoryVolunteerDirectory volunteers = new InMemoryVolunteerDirectory();
    public final InMemoryAvailability availability = new InMemoryAvailability(volunteers);
    public final InMemoryRefillRepository refills = new InMemoryRefillRepository();
    public final FakeChatNotifier chat;
    public final FakeHrSystem hr = new FakeHrSystem();
    public final FixedFxRateProvider fx;
    public final BudgetService budget;
    public final VolunteerSelector selector;
    public final RefillService service;
    public final ReportService reports;

    public SnackSystem(SnackConfig config, Instant start, boolean echoChat) {
        this.config = config;
        this.clock = new MutableClock(start);
        this.chat = new FakeChatNotifier(echoChat);
        // Illustrative rate: 1 INR = 0.125 NOK (i.e. 8 INR per NOK). Configurable in production.
        this.fx = new FixedFxRateProvider(Currency.getInstance("NOK"))
                .withRate(Currency.getInstance("INR"), "0.125");
        this.budget = new BudgetService(config, refills);
        this.selector = new VolunteerSelector(config, volunteers, availability, refills);
        this.service = new RefillService(config, refills, volunteers, selector, budget, chat, hr, fx, clock);
        this.reports = new ReportService(config, refills);
    }

    public static SnackSystem withDefaults(Instant start) {
        return new SnackSystem(SnackConfig.defaults(), start, false);
    }
}
