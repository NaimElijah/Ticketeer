package com.ticketing.system.ui.security;

import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.vaadin.flow.server.VaadinRequest;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Single-source-of-truth resolver for the current session's
 * {@link Capability} set.
 *
 * <p>
 * Callers use {@link #has(Capability)} from anywhere — drawer
 * builders, view bodies, button visibility checks. The resolution
 * itself reads the existing session-scoped state ({@link AuthSession},
 * {@link CurrentCompanies}) so this layer doesn't introduce new data;
 * it only synthesises a derived view of it.
 *
 * <p>A member's companies are read once per request, not once per check: a page asks for several
 * capabilities (My Events, once per row), and each read loads every company with its active-event
 * count. The read is kept as a {@link VaadinRequest} attribute for the token it was made with, so a
 * sign-in or sign-out in the same request is never answered from it, and {@link AuthBootstrap} drops it
 * at the start of each navigation, so a navigation sees the companies as they are then. Without a
 * current request (a background thread) every check reads afresh.
 */
public final class Capabilities {

    private static final String MEMBERSHIPS_ATTRIBUTE = Capabilities.class.getName() + ".memberships";

    private Capabilities() {
    }

    /** Capabilities a logged-out visitor automatically holds. */
    private static final Set<Capability> GUEST_BASE = unmodifiable(EnumSet.of(
            Capability.BROWSE_CATALOG,
            Capability.VIEW_EVENT_DETAILS,
            Capability.VIEW_SEAT_MAP,
            Capability.USE_CART));

    /** Capabilities every signed-in member holds, on top of {@link #GUEST_BASE}. */
    private static final Set<Capability> MEMBER_BASE = unmodifiable(EnumSet.of(
            Capability.CHECKOUT,
            Capability.MEMBER_ACCOUNT,
            Capability.SUBMIT_COMPLAINT,
            Capability.REGISTER_COMPANY,
            Capability.MANAGE_INVITATIONS));

    /** What founders + co-owners hold for their current company. */
    private static final Set<Capability> OWNER_BUNDLE = unmodifiable(EnumSet.of(
            Capability.VIEW_COMPANY_EVENTS,
            Capability.EDIT_COMPANY_EVENTS,
            Capability.VIEW_COMPANY_SALES,
            Capability.EDIT_PURCHASE_POLICIES,
            Capability.RESPOND_INQUIRIES,
            Capability.MANAGE_VENUE_MAPS,
            Capability.APPOINT_MANAGER,
            Capability.APPOINT_CO_OWNER,
            Capability.EDIT_MANAGER_PERMISSIONS,
            Capability.REVOKE_MANAGER,
            Capability.CANCEL_EVENT,
            Capability.VIEW_COMPANY_ORG_TREE));

    /** Extra capabilities the founder holds on top of {@link #OWNER_BUNDLE}. */
    private static final Set<Capability> FOUNDER_EXTRA = unmodifiable(EnumSet.of(
            Capability.DISSOLVE_COMPANY,
            Capability.TRANSFER_FOUNDERSHIP));

    /**
     * Every capability that comes with company membership: the workspace itself and all a founder holds
     * in it, which covers what any co-owner or manager can hold. {@link AuthBootstrap} reads it to pick
     * the workspace fallback, so a capability added to a bundle above is a workspace capability there too.
     */
    static final Set<Capability> WORKSPACE_CAPABILITIES = unmodifiable(workspaceCapabilities());

    /** Platform-admin capability bundle. {@link AuthBootstrap} reads it to pick the admin sign-in. */
    static final Set<Capability> ADMIN_BUNDLE = unmodifiable(EnumSet.of(
            Capability.ADMIN_WORKSPACE,
            Capability.VIEW_GLOBAL_HISTORY,
            Capability.BROADCAST_ANNOUNCEMENT,
            Capability.MANAGE_COMPLAINTS,
            Capability.VIEW_ORG_TREES));

    /** Whether the current session holds {@code c}; see {@link #forCurrentUser()}. */
    public static boolean has(Capability c) {
        return forCurrentUser().contains(c);
    }

    /** Whether the current session holds every capability in {@code cs}; true for none. */
    public static boolean hasAll(Capability... cs) {
        Set<Capability> mine = forCurrentUser();
        for (Capability c : cs) {
            if (!mine.contains(c)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the current session holds at least one capability in {@code cs}; false for none. */
    public static boolean hasAny(Capability... cs) {
        Set<Capability> mine = forCurrentUser();
        for (Capability c : cs) {
            if (mine.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The capabilities of the current session, as a new set on every call.
     *
     * <ul>
     *   <li>A signed-out visitor holds the guest capabilities.</li>
     *   <li>An admin holds those and the admin capabilities, and no company capability.</li>
     *   <li>A member holds the guest and member capabilities, plus the workspace and role
     *       capabilities of the current company, or of their first company otherwise; a member with
     *       no company, or whose token is dead, gets no company capability.</li>
     * </ul>
     *
     * <p>Side effect: for a member it reads the member's companies from the organization service, once
     * per request (class comment). A failure other than a dead token propagates.
     */
    public static Set<Capability> forCurrentUser() {
        Set<Capability> caps = EnumSet.copyOf(GUEST_BASE);

        if (AuthSession.isAdmin()) {
            // Admins are a SEPARATE pool from members, and an admin token is no member credential
            // (ADR-012): listForUser refuses it. An admin holds no company memberships, so never ask.
            caps.addAll(ADMIN_BUNDLE);
            return caps;
        }

        if (!AuthSession.isSignedIn()) {
            return caps;
        }

        caps.addAll(MEMBER_BASE);

        String token = AuthSession.token();
        CompanyManagementService service = CompanyManagementBridge.service();
        if (token == null || service == null) {
            return caps;
        }

        List<UserCompanyDTO> memberships = membershipsOnThisRequest(token, service);
        if (memberships.isEmpty()) {
            return caps;
        }

        Integer currentId = CurrentCompanies.currentCompanyId();
        UserCompanyDTO current = null;
        if (currentId != null) {
            for (UserCompanyDTO m : memberships) {
                if (m.companyId() == currentId) {
                    current = m;
                    break;
                }
            }
        }
        if (current == null) {
            current = memberships.get(0);
        }

        caps.add(Capability.OWNER_WORKSPACE);

        // A switch expression, so a new MembershipRole fails the build until it is given its capabilities.
        Set<Capability> roleCapabilities = switch (current.role()) {
            case FOUNDER -> {
                Set<Capability> founder = EnumSet.copyOf(OWNER_BUNDLE);
                founder.addAll(FOUNDER_EXTRA);
                yield founder;
            }
            case CO_OWNER -> OWNER_BUNDLE;
            case MANAGER -> capabilitiesFromPermissions(current.managerPermissions());
        };
        caps.addAll(roleCapabilities);
        return caps;
    }

    /**
     * Forgets the companies read on the current request, so the next check reads them again.
     * {@link AuthBootstrap} calls it as each navigation starts: an action earlier in the request (a
     * company registered, an invitation accepted) may have changed them.
     */
    static void forgetMembershipsOfThisRequest() {
        VaadinRequest request = VaadinRequest.getCurrent();
        if (request != null) {
            request.removeAttribute(MEMBERSHIPS_ATTRIBUTE);
        }
    }

    private static List<UserCompanyDTO> membershipsOnThisRequest(String token, CompanyManagementService service) {
        VaadinRequest request = VaadinRequest.getCurrent();
        if (request != null && request.getAttribute(MEMBERSHIPS_ATTRIBUTE) instanceof MembershipsRead read
                && read.token().equals(token)) {
            return read.memberships();
        }
        List<UserCompanyDTO> memberships = readMemberships(token, service);
        if (request != null) {
            request.setAttribute(MEMBERSHIPS_ATTRIBUTE, new MembershipsRead(token, memberships));
        }
        return memberships;
    }

    private static List<UserCompanyDTO> readMemberships(String token, CompanyManagementService service) {
        try {
            return List.copyOf(service.listForUser(token));
        } catch (InvalidTokenException e) {
            // The token died after sign-in: revoked or expired (a SessionExpiredException is an InvalidTokenException).
            // A dead token holds no memberships, so it gets no company capabilities; AuthBootstrap signs the
            // visitor out on their next navigation. Any other failure is not a dead token and propagates.
            return List.of();
        }
    }

    // The token is a credential, so it stays out of toString.
    private record MembershipsRead(String token, List<UserCompanyDTO> memberships) {
        @Override
        public String toString() {
            return "MembershipsRead[memberships=" + memberships.size() + "]";
        }
    }

    private static Set<Capability> capabilitiesFromPermissions(List<Permission> permissions) {
        Set<Capability> caps = EnumSet.noneOf(Capability.class);
        if (permissions == null) {
            return caps;
        }
        // Every manager permission grants VIEW_COMPANY_EVENTS: "My Events" is the read-only hub
        // every manager lands on, and the per-row actions there are each gated by the specific
        // capability below, so a manager only sees the buttons for what their permissions allow.
        for (Permission permission : permissions) {
            caps.add(Capability.VIEW_COMPANY_EVENTS);
            switch (permission) {
                case VIEW_SALES -> caps.add(Capability.VIEW_COMPANY_SALES);
                case EDIT_POLICIES -> caps.add(Capability.EDIT_PURCHASE_POLICIES);
                case RESPOND_TO_INQUIRIES -> caps.add(Capability.RESPOND_INQUIRIES);
                case CONFIGURE_VENUE -> caps.add(Capability.MANAGE_VENUE_MAPS);
                case MANAGE_INVENTORY -> {
                    // Full event lifecycle: edit metadata, create, change status, remove
                    // (EDIT_COMPANY_EVENTS) and cancel + refund (CANCEL_EVENT).
                    caps.add(Capability.EDIT_COMPANY_EVENTS);
                    caps.add(Capability.CANCEL_EVENT);
                }
            }
        }
        return caps;
    }

    private static Set<Capability> workspaceCapabilities() {
        Set<Capability> workspace = EnumSet.of(Capability.OWNER_WORKSPACE);
        workspace.addAll(OWNER_BUNDLE);
        workspace.addAll(FOUNDER_EXTRA);
        return workspace;
    }

    private static Set<Capability> unmodifiable(Set<Capability> src) {
        return Collections.unmodifiableSet(src);
    }
}
