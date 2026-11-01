package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.Toasts;

/**
 * Proves {@link InboxViewHarness} reads the toasts a view shows, so an inbox view test that expects
 * none cannot pass only because the harness sees none.
 */
class InboxViewHarnessTest {

    private final InboxViewHarness harness = new InboxViewHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTwoToastsShown_WhenTheHarnessReadsThem_ThenItReturnsEachTextInOrder() {
        Toasts.failure("first");
        Toasts.success("second");

        assertEquals(List.of("first", "second"), harness.toasts());
    }

    @Test
    void GivenNoToastShown_WhenTheHarnessReadsThem_ThenItReturnsNone() {
        assertEquals(List.of(), harness.toasts());
    }
}
