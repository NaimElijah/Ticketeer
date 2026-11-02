package com.ticketing.system.ui.components.company;

import com.vaadin.flow.component.confirmdialog.ConfirmDialog;

/**
 * The confirmation an owner passes before an event is cancelled. Cancelling refunds every ticket holder
 * and cannot be undone, so every way to cancel goes through this one dialog (#187, UI-A4): My Events
 * ({@code CompanyEventListView}) and the event's management page ({@code EventManagementView}).
 */
public final class EventCancelConfirm {

    private EventCancelConfirm() { }

    /**
     * Opens the confirmation for one event.
     *
     * @param eventName the event's name, shown in the dialog's header
     * @param onConfirm runs only when the owner confirms; never on "Keep event" or a dismissal
     */
    public static void open(String eventName, Runnable onConfirm) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Cancel \"" + eventName + "\"?");
        dialog.setText("This will refund all ticket holders and permanently cancel the event. This cannot be undone.");
        dialog.setCancelable(true);
        dialog.setCancelText("Keep event");
        dialog.setConfirmText("Cancel event");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(ignored -> onConfirm.run());
        dialog.open();
    }
}
