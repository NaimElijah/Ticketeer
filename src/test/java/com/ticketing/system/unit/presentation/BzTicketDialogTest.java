package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.buyer.BzTicketDialog;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;

/**
 * {@link BzTicketDialog} opened headless on {@link InboxViewHarness}'s UI, which attaches the dialog
 * when its recorded before-response tasks run. Its callers ({@code MyAccountView}, {@code ReceiptView})
 * pass the order reference already prefixed with "#".
 */
class BzTicketDialogTest {

    private InboxViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new InboxViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- UI-B12: receipts read "Order #42", not "Order ##42" (#187) ----

    @Test
    void GivenOrder42AsTheCallersPassIt_WhenShown_ThenTheTicketReadsOrderHash42() {
        BzTicketDialog.show(new BzTicketDialog.TicketInfo("Coldplay", "concert", "Sat 1 May 2027, 20:00",
                "Park HaYarkon, Tel Aviv", "Floor", "A1", "$75.00", "TKT-0042-A1", "#42"));

        harness.toasts();
        List<String> orderLines = InboxViewHarness.find(UI.getCurrent(), Dialog.class).stream()
                .flatMap(dialog -> InboxViewHarness.find(dialog, Span.class).stream())
                .map(Span::getText)
                .filter(text -> text.startsWith("Order"))
                .toList();
        assertEquals(List.of("Order #42"), orderLines);
    }
}
