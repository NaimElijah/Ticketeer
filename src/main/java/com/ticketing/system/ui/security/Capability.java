package com.ticketing.system.ui.security;

/**
 * Fine-grained capabilities the UI consults to decide whether a section,
 * button, drawer item, or whole view should be visible to the current
 * session.
 *
 * <p>Capabilities are <em>not</em> roles. A role (Founder, Co-owner,
 * Manager) is a bundle of capabilities; persona-level routing markers
 * ({@link RequireCapability}, {@link RequireCapability}) gate broad
 * URL spaces. Capabilities answer "can this user do <em>this specific
 * thing</em>" and let one view adapt to multiple roles without
 * branching on enum strings.
 *
 * <p>Resolution: {@link Capabilities#forCurrentUser()} maps the
 * combined {@code AuthSession + CurrentCompanies + MockPermissions}
 * state to a {@code Set<Capability>}. {@link Capabilities#has(Capability)}
 * is the lookup callers use.
 */
public enum Capability {

    // ---- Universal (guest can see) ----
    BROWSE_CATALOG,
    VIEW_EVENT_DETAILS,
    VIEW_SEAT_MAP,
    USE_CART,

    // ---- Member (signed in, any persona) ----
    CHECKOUT,
    MEMBER_ACCOUNT,
    SUBMIT_COMPLAINT,
    REGISTER_COMPANY,
    MANAGE_INVITATIONS,

    // ---- Owner workspace access (any role with a company) ----
    OWNER_WORKSPACE,

    // ---- Manager-grantable (also held by Founder + Co-owner unconditionally) ----
    VIEW_COMPANY_EVENTS,
    EDIT_COMPANY_EVENTS,
    VIEW_COMPANY_SALES,
    EDIT_PURCHASE_POLICIES,
    RESPOND_INQUIRIES,
    MANAGE_VENUE_MAPS,
    // Rides with MANAGE_INVENTORY as part of the full event lifecycle. Cancelling an event refunds
    // every buyer, so it is a weighty action — but it belongs to whoever manages the event, not to a
    // separate owner-only tier. Owners hold it unconditionally via OWNER_BUNDLE.
    CANCEL_EVENT,

    // ---- Owner-only (Founder + Co-owner; NOT manager-grantable) ----
    APPOINT_MANAGER,
    APPOINT_CO_OWNER,
    EDIT_MANAGER_PERMISSIONS,
    REVOKE_MANAGER,
    VIEW_COMPANY_ORG_TREE,

    // ---- Founder-only ----
    DISSOLVE_COMPANY,
    TRANSFER_FOUNDERSHIP,

    // ---- Platform admin ----
    ADMIN_WORKSPACE,
    VIEW_GLOBAL_HISTORY,
    BROADCAST_ANNOUNCEMENT,
    MANAGE_COMPLAINTS,
    VIEW_ORG_TREES,
    ADMIN_SETTINGS
}
