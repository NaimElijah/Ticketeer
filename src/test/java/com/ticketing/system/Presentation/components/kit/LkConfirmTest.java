package com.ticketing.system.ui.components.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link LkConfirm}'s future contract. The dialog can't be
 * {@code open()}ed without a live UI, so these drive the package-private
 * {@code settle(...)} seam directly — exactly what the Cancel/Confirm buttons
 * and ESC/click-outside dismissal call.
 */
class LkConfirmTest {

    @Test
    void newDialog_resultFutureStartsIncomplete() {
        LkConfirm dialog = new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger);
        assertFalse(dialog.result().isDone(), "future must not resolve before a decision");
    }

    @Test
    void settleTrue_completesFutureWithTrue() {
        LkConfirm dialog = new LkConfirm("Revoke", "Sure?", LkConfirm.Severity.danger)
            .confirmText("Revoke");

        dialog.settle(true);

        assertTrue(dialog.result().isDone());
        assertEquals(Boolean.TRUE, dialog.result().getNow(null));
    }

    @Test
    void settleFalse_completesFutureWithFalse() {
        LkConfirm dialog = new LkConfirm("Clear cart", "Remove all items?", LkConfirm.Severity.warn);

        dialog.settle(false);

        assertTrue(dialog.result().isDone());
        assertEquals(Boolean.FALSE, dialog.result().getNow(null));
    }

    @Test
    void settle_isIdempotent_firstDecisionWins() {
        LkConfirm dialog = new LkConfirm("Heads up", "Proceed?", LkConfirm.Severity.info);

        dialog.settle(true);
        dialog.settle(false); // a later dismissal must not overwrite the confirm

        assertEquals(Boolean.TRUE, dialog.result().getNow(null));
    }

    @Test
    void builders_areChainableAcrossAllSeverities() {
        for (LkConfirm.Severity sev : LkConfirm.Severity.values()) {
            LkConfirm dialog = new LkConfirm("Title", "Body", sev)
                .severity(sev)
                .confirmText("Yes")
                .cancelText("No")
                .addToBody(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 16), "context"));
            assertFalse(dialog.result().isDone());
        }
    }
}
