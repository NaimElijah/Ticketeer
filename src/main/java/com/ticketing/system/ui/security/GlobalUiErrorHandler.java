package com.ticketing.system.ui.security;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.support.ServiceErrors;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Installs a session-wide Vaadin {@link com.vaadin.flow.server.ErrorHandler} so that an uncaught
 * exception during <i>any</i> UI interaction shows a friendly toast instead of Vaadin's default
 * internal-error popup (or a raw stack trace in dev mode). A database outage gets the specific
 * "temporarily unavailable" message; anything else gets a generic apology. The full stack is always
 * logged server-side, so developers still see the detail in the logs.
 *
 * <p>Registered exactly like {@link GuestSessionBootstrap}: a {@link VaadinServiceInitListener} that
 * sets the handler on every new {@code VaadinSession}. This is the safety net behind the per-flow
 * handling (e.g. the login presenters) — it catches whatever a view forgot to.
 */
@Component
@Slf4j
public class GlobalUiErrorHandler implements VaadinServiceInitListener {

    static final String GENERIC_MESSAGE = "Something went wrong — please try again.";

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(sessionInit ->
            sessionInit.getSession().setErrorHandler(this::handle));
    }

    private void handle(ErrorEvent errorEvent) {
        Throwable error = errorEvent.getThrowable();
        if (ServiceErrors.isDatabaseUnavailable(error)) {
            log.warn("UI request failed — database unavailable: {}", rootCauseMessage(error));
        } else {
            log.error("Unhandled error during a UI request", error);
        }
        showToast(messageFor(error));
    }

    /** The user-facing message for an uncaught error — friendly either way, specific for a DB outage. */
    static String messageFor(Throwable error) {
        return ServiceErrors.isDatabaseUnavailable(error)
            ? ServiceErrors.DB_UNAVAILABLE_MESSAGE
            : GENERIC_MESSAGE;
    }

    private void showToast(String message) {
        UI ui = UI.getCurrent();
        if (ui != null) {
            // We hold the session lock here; access() runs the toast before the response is sent.
            ui.access(() -> Toasts.failure(message));
        } else {
            log.debug("no active UI on the error thread — toast suppressed (error already logged)");
        }
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
