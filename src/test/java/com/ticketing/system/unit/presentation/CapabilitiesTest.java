package com.ticketing.system.unit.presentation;

import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity checks for the capability resolver. We can't easily fake a
 * VaadinSession inside a unit test, so the assertions here cover the
 * static structure (bundle sets, helper booleans) — the per-role bundle
 * compositions and the marker / annotation wiring are exercised by
 * VaadinSmokeTest's view-construction pass.
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
