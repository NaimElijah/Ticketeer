package com.ticketing.system.ui.session;

import com.ticketing.system.ui.security.Capability;
import com.vaadin.flow.server.VaadinSession;

import java.util.EnumSet;
import java.util.Set;

/**
 * Per-company manager-permission grants stored on {@link VaadinSession}.
 *
 * <p>Founders and co-owners hold the full owner-capability set by
 * virtue of their role; only <b>managers</b> need explicit grants. The
 * {@link com.ticketing.system.ui.security.Capabilities}
 * resolver consults this store only when the current company's role
 * is "Manager".
 *
 * <p>{@link #GRANTABLE} pins the closed set of capabilities an owner
 * is allowed to hand to a manager — capabilities outside this set
 * (e.g. {@link Capability#APPOINT_CO_OWNER},
 * {@link Capability#DISSOLVE_COMPANY}) are owner-only by design.
 */
public final class MockPermissions {

    /** Capabilities that an owner can grant to a manager. */
    public static final Set<Capability> GRANTABLE = EnumSet.of(
        Capability.VIEW_COMPANY_EVENTS,
        Capability.EDIT_COMPANY_EVENTS,
        Capability.VIEW_COMPANY_SALES,
        Capability.EDIT_PURCHASE_POLICIES,
        Capability.RESPOND_INQUIRIES,
        Capability.MANAGE_VENUE_MAPS
    );

    /** Default grant for a fresh manager mock — a sensible "browse-only" baseline. */
    private static final Set<Capability> DEFAULT_GRANTS = EnumSet.of(
        Capability.VIEW_COMPANY_EVENTS,
        Capability.RESPOND_INQUIRIES
    );

    private static final String KEY_PREFIX = "mockPermissions.";

    private MockPermissions() { }

    @SuppressWarnings("unchecked")
    public static Set<Capability> forCompany(String companyId) {
        if (companyId == null) return EnumSet.noneOf(Capability.class);
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return EnumSet.noneOf(Capability.class);

        String key = KEY_PREFIX + companyId;
        Set<Capability> set = (Set<Capability>) s.getAttribute(key);
        if (set == null) {
            set = EnumSet.copyOf(DEFAULT_GRANTS);
            s.setAttribute(key, set);
        }
        return set;
    }

    public static Set<Capability> forCurrentCompany() {
        return forCompany(CurrentCompanies.currentCompanyIdAsString());
    }

    public static void grant(String companyId, Capability c) {
        if (!GRANTABLE.contains(c))
            throw new IllegalArgumentException("Not grantable to managers: " + c);
        forCompany(companyId).add(c);
    }

    public static void revoke(String companyId, Capability c) {
        forCompany(companyId).remove(c);
    }

    /** Replace the manager's grant set wholesale (used by the edit-permissions dialog). */
    public static void setAll(String companyId, Set<Capability> caps) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        Set<Capability> filtered = EnumSet.noneOf(Capability.class);
        for (Capability c : caps) if (GRANTABLE.contains(c)) filtered.add(c);
        s.setAttribute(KEY_PREFIX + companyId, filtered);
    }
}
