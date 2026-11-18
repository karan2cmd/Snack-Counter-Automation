package com.framsikt.snack.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefillStatusTest {

    private static final Instant NOW = Instant.parse("2026-11-02T08:00:00Z");

    @Test
    void cannot_skip_from_offered_straight_to_claim() {
        assertThat(RefillStatus.OFFERED.canMoveTo(RefillStatus.CLAIM_SUBMITTED)).isFalse();
    }

    @Test
    void terminal_states_have_no_exits() {
        for (RefillStatus s : RefillStatus.values()) {
            assertThat(RefillStatus.REIMBURSED.canMoveTo(s)).isFalse();
            assertThat(RefillStatus.CLAIM_REJECTED.canMoveTo(s)).isFalse();
        }
    }

    @Test
    void entity_rejects_invalid_transition() {
        RefillRequest r = new RefillRequest("R-1", "OSL", "x", NOW);
        assertThatThrownBy(() -> r.claimSubmitted("HR-1", NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
