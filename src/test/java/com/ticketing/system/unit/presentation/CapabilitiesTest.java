package com.ticketing.system.unit.presentation;

import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks for the capability resolver. The guest checks run with no VaadinSession at all; the
 * signed-in checks install a map-backed session ({@link MapBackedVaadinSession}) and a mocked
 * {@link CompanyManagementService} behind {@link CompanyManagementBridge}. The marker / annotation
 * wiring is exercised by VaadinSmokeTest's view-construction pass.
 */
class CapabilitiesTest {

    @Test
    void resolverIsCallableWithoutSession() {
        Set<Capability> caps = Capabilities.forCurrentUser();
        assertNotNull(caps, "Capabilities.forCurrentUser() must never return null");
    }

    @Test
    void guestBaseIncludesCatalogButNotCheckout() {
        // No VaadinSession in this test → resolver short-circuits to GUEST_BASE.
        Set<Capability> caps = Capabilities.forCurrentUser();
        assertTrue(caps.contains(Capability.BROWSE_CATALOG),
            "Guest must be able to browse the catalog");
        assertFalse(caps.contains(Capability.CHECKOUT),
            "Guest must NOT have CHECKOUT — that's a member capability");
        assertFalse(caps.contains(Capability.OWNER_WORKSPACE),
            "Guest must NOT have OWNER_WORKSPACE — that requires a company");
        assertFalse(caps.contains(Capability.ADMIN_WORKSPACE),
            "Guest must NOT have ADMIN_WORKSPACE — admins go through their own pool");
    }

    @Test
    void hasAndHasAllAndHasAnyAreConsistent() {
        // With only the guest baseline available, these helpers must agree.
        assertTrue(Capabilities.has(Capability.BROWSE_CATALOG));
        assertTrue(Capabilities.hasAll(Capability.BROWSE_CATALOG, Capability.VIEW_EVENT_DETAILS));
        assertTrue(Capabilities.hasAny(Capability.BROWSE_CATALOG, Capability.CHECKOUT));
        assertFalse(Capabilities.hasAll(Capability.BROWSE_CATALOG, Capability.CHECKOUT));
        assertFalse(Capabilities.hasAny(Capability.CHECKOUT, Capability.OWNER_WORKSPACE));
    }

    // ---- manager permission → capability mapping (the workspace gating matrix) ----
    // capabilitiesFromPermissions is private static; we reflectively exercise the pure mapping so the
    // matrix is pinned without faking a VaadinSession (which forCurrentUser would require).

    @Test
    void everyManagerPermissionGrantsTheReadOnlyEventsHub() {
        for (Permission p : Permission.values()) {
            assertTrue(capsFor(p).contains(Capability.VIEW_COMPANY_EVENTS),
                p + " must grant VIEW_COMPANY_EVENTS — every manager lands on the My Events hub");
        }
    }

    @Test
    void eachManagerPermissionGrantsExactlyItsOwnAction() {
        assertEquals(EnumSet.of(Capability.VIEW_COMPANY_EVENTS, Capability.VIEW_COMPANY_SALES),
            capsFor(Permission.VIEW_SALES));
        assertEquals(EnumSet.of(Capability.VIEW_COMPANY_EVENTS, Capability.EDIT_PURCHASE_POLICIES),
            capsFor(Permission.EDIT_POLICIES));
        assertEquals(EnumSet.of(Capability.VIEW_COMPANY_EVENTS, Capability.RESPOND_INQUIRIES),
            capsFor(Permission.RESPOND_TO_INQUIRIES));
        assertEquals(EnumSet.of(Capability.VIEW_COMPANY_EVENTS, Capability.MANAGE_VENUE_MAPS),
            capsFor(Permission.CONFIGURE_VENUE));
    }

    @Test
    void manageInventoryOwnsTheFullEventLifecycleIncludingCancel() {
        Set<Capability> caps = capsFor(Permission.MANAGE_INVENTORY);
        assertTrue(caps.contains(Capability.VIEW_COMPANY_EVENTS));
        assertTrue(caps.contains(Capability.EDIT_COMPANY_EVENTS));
        assertTrue(caps.contains(Capability.CANCEL_EVENT),
            "MANAGE_INVENTORY grants cancel+refund as part of the event lifecycle");
    }

    @Test
    void aSalesOnlyManagerCannotEditOrCancelEvents() {   // no privilege escalation through the shared hub
        Set<Capability> caps = capsFor(Permission.VIEW_SALES);
        assertFalse(caps.contains(Capability.EDIT_COMPANY_EVENTS),
            "VIEW_SALES sees My Events but must not edit them");
        assertFalse(caps.contains(Capability.CANCEL_EVENT),
            "VIEW_SALES must not be able to cancel/refund events");
    }

    @Test
    void combinedPermissionsGrantTheUnionOfTheirCapabilities() {
        Set<Capability> caps = capsFor(Permission.VIEW_SALES, Permission.MANAGE_INVENTORY);
        assertTrue(caps.containsAll(EnumSet.of(
            Capability.VIEW_COMPANY_EVENTS, Capability.VIEW_COMPANY_SALES,
            Capability.EDIT_COMPANY_EVENTS, Capability.CANCEL_EVENT)),
            "a manager holding both permissions gets the union of their capabilities");
    }

    // ---- resolution for a signed-in session: memberships are read with the member's own token ----

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final int USER_ID = 42;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;

    private CompanyManagementService service;
    // The bridge is static and a Spring context in the same JVM may have set it, so it is restored after.
    private CompanyManagementService bridgedBefore;

    @AfterEach
    void restoreSessionAndBridge() {
        if (service != null) {
            new CompanyManagementBridge(bridgedBefore);
            MapBackedVaadinSession.uninstall();
        }
    }

    @Test
    void GivenAdminSession_WhenForCurrentUser_ThenAdminBundleAndNoMembershipLookup() {
        installSessionAndService();
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, USER_ID, "admin", true));

        Set<Capability> caps = Capabilities.forCurrentUser();

        assertTrue(caps.contains(Capability.ADMIN_WORKSPACE));
        assertFalse(caps.contains(Capability.OWNER_WORKSPACE), "an admin holds no company memberships");
        verify(service, never()).listForUser(any());
    }

    @Test
    void GivenMemberOfNoCompany_WhenForCurrentUser_ThenMemberBaseAskedWithTheMembersToken() {
        signInAsMember();
        when(service.listForUser(MEMBER_TOKEN)).thenReturn(List.of());

        Set<Capability> caps = Capabilities.forCurrentUser();

        assertTrue(caps.contains(Capability.CHECKOUT));
        assertFalse(caps.contains(Capability.OWNER_WORKSPACE));
        verify(service).listForUser(MEMBER_TOKEN);
    }

    @Test
    void GivenMemberWhoseTokenDied_WhenForCurrentUser_ThenMemberBaseWithoutCompanyCapabilities() {
        signInAsMember();
        when(service.listForUser(MEMBER_TOKEN)).thenThrow(new InvalidTokenException("Invalid token"));

        Set<Capability> caps = Capabilities.forCurrentUser();

        assertTrue(caps.contains(Capability.CHECKOUT));
        assertFalse(caps.contains(Capability.OWNER_WORKSPACE), "a dead token holds no memberships");
    }

    private void installSessionAndService() {
        MapBackedVaadinSession.install();
        bridgedBefore = CompanyManagementBridge.service();
        service = mock(CompanyManagementService.class);
        new CompanyManagementBridge(service);
    }

    private void signInAsMember() {
        installSessionAndService();
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, TOKEN_EXPIRY_EPOCH_MILLIS, USER_ID, "member"));
    }

    @SuppressWarnings("unchecked")
    private static Set<Capability> capsFor(Permission... permissions) {
        try {
            Method m = Capabilities.class.getDeclaredMethod("capabilitiesFromPermissions", List.class);
            m.setAccessible(true);
            return (Set<Capability>) m.invoke(null, List.of(permissions));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not invoke Capabilities.capabilitiesFromPermissions", e);
        }
    }
}
