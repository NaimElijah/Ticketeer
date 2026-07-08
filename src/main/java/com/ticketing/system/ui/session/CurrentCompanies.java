package com.ticketing.system.ui.session;
import com.ticketing.system.identity.domain.Session;

import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped state for which of the user's companies is currently
 * in focus. Drives capability resolution and owner UI defaults.
 */
public final class CurrentCompanies {

    private static final String CURRENT_ID_KEY = "currentCompanies.currentCompanyId";

    private CurrentCompanies() { }

    public static Integer currentCompanyId() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null) return null;
        return (Integer) session.getAttribute(CURRENT_ID_KEY);
    }

    public static String currentCompanyIdAsString() {
        Integer id = currentCompanyId();
        return id == null ? null : String.valueOf(id);
    }

    public static void setCurrentCompany(int companyId) {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) session.setAttribute(CURRENT_ID_KEY, companyId);
    }

    public static void clearCurrentCompany() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session != null) session.setAttribute(CURRENT_ID_KEY, null);
    }
}
