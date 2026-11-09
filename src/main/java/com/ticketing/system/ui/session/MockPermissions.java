package com.ticketing.system.ui.session;

import com.ticketing.system.ui.security.Capability;
import com.vaadin.flow.server.VaadinSession;

import java.util.EnumSet;
import java.util.Set;

/**
 * Per-company manager-permission grants stored on {@link VaadinSession}.
 *
 * <p>{@link com.ticketing.system.ui.security.Capabilities} does not consult
 * the grant store: it resolves a manager's capabilities from the permissions
 * the organization service reports for the current company. Of this class,
 * only {@link #GRANTABLE} is read by the application, by {@code DevPanel}.
 *
 * <p>{@link #GRANTABLE} is the closed set of capabilities this store accepts
 * as grants; {@link #grant} refuses any other, such as
 * {@link Capability#APPOINT_CO_OWNER} or {@link Capability#DISSOLVE_COMPANY}.
 */
public final class MockPermissions {

    /** The capabilities this store accepts as grants; {@code DevPanel} lists them. */
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

    /**
     * The grant set the session holds for a company, created on first use with a default of
     * {@code VIEW_COMPANY_EVENTS} and {@code RESPOND_INQUIRIES}. The set is the stored one, not a copy.
     *
     * @param companyId the company's id as text; {@code null} gives an empty set that is not stored
     */
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

    /** {@link #forCompany(String)} for the company in focus ({@code CurrentCompanies}). */
    public static Set<Capability> forCurrentCompany() {
        return forCompany(CurrentCompanies.currentCompanyIdAsString());
    }

    /**
     * Adds {@code c} to the company's grants.
     *
     * @throws IllegalArgumentException if {@code c} is not in {@link #GRANTABLE}
     */
    public static void grant(String companyId, Capability c) {
        if (!GRANTABLE.contains(c))
            throw new IllegalArgumentException("Not grantable to managers: " + c);
        forCompany(companyId).add(c);
    }

    /** Removes {@code c} from the company's grants. */
    public static void revoke(String companyId, Capability c) {
        forCompany(companyId).remove(c);
    }

    /**
     * Replaces the company's grant set wholesale with those of {@code caps} that are in
     * {@link #GRANTABLE}; the others are dropped without an error.
     */
    public static void setAll(String companyId, Set<Capability> caps) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        Set<Capability> filtered = EnumSet.noneOf(Capability.class);
        for (Capability c : caps) if (GRANTABLE.contains(c)) filtered.add(c);
        s.setAttribute(KEY_PREFIX + companyId, filtered);
    }
}
