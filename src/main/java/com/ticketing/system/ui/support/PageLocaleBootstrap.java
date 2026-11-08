package com.ticketing.system.ui.support;

import org.springframework.stereotype.Component;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Starts every UI in {@link DisplayLocale#PAGE}, so Vaadin's own components format in it too.
 *
 * <p>Vaadin gives a new session, and every UI in it, the server JVM's default locale unless the app has an
 * {@code I18NProvider}, which this one has not: on a server set to German the date pickers showed and parsed
 * "9.10.2026" on an English page. They read the UI's locale, a path {@code HexagonalRulesTest}'s locale rule
 * cannot see, so it is set here. Vaadin runs this after it copies the session's locale to the UI and before the
 * UI's first navigation (ADR-027, #189).
 */
@Component
public class PageLocaleBootstrap implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInit -> uiInit.getUI().setLocale(DisplayLocale.PAGE));
    }
}
