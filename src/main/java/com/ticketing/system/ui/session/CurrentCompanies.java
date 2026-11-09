package com.ticketing.system.ui.session;

import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped state for which of the user's companies is currently
 * in focus. Drives capability resolution and owner UI defaults.
 */
public final class CurrentCompanies {

    private static final String CURRENT_ID_KEY = "currentCompanies.currentCompanyId";

    private CurrentCompanies() { }

    /** The id of the company in focus; {@code null} when none was chosen or there is no Vaadin session. */
    public static Integer currentCompanyId() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) return null;
        return (Integer) session.getAttribute(CURRENT_ID_KEY);
    }

    /** {@link #currentCompanyId()} as text; {@code null} when there is none. */
    public static String currentCompanyIdAsString() {
        Integer id = currentCompanyId();
        return id == null ? null : String.valueOf(id);
    }

    /** Puts the company in focus; does nothing without a Vaadin session. It checks no membership. */
    public static void setCurrentCompany(int companyId) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) session.setAttribute(CURRENT_ID_KEY, companyId);
    }

    /** Leaves no company in focus; does nothing without a Vaadin session. */
    public static void clearCurrentCompany() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) session.setAttribute(CURRENT_ID_KEY, null);
    }
}
