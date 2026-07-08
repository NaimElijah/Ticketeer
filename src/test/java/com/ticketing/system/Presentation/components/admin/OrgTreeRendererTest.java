package com.ticketing.system.ui.components.admin;

import com.ticketing.system.Core.Application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.Core.Domain.users.Permission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for {@link OrgTreeRenderer}.
 * Pure JUnit — no Spring context needed; Vaadin components can be instantiated headlessly.
 */
class OrgTreeRendererTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private static OrganizationalTreeNodeDTO leaf(int id, String username, String role, boolean isFounder) {
        return new OrganizationalTreeNodeDTO(id, username, role, isFounder, List.of(), new ArrayList<>());
    }

    private static OrganizationalTreeNodeDTO node(int id, String username, String role, boolean isFounder,
                                                   List<Permission> perms,
                                                   List<OrganizationalTreeNodeDTO> children) {
        return new OrganizationalTreeNodeDTO(id, username, role, isFounder, perms, new ArrayList<>(children));
    }

    // ── construction ───────────────────────────────────────────────────────────

    @Test
    void founderLeaf_constructsWithoutError() {
        OrganizationalTreeNodeDTO founder = leaf(1, "Alice Cohen", "Owner", true);
        assertDoesNotThrow(() -> new OrgTreeRenderer(founder));
    }

    @Test
    void ownerLeaf_constructsWithoutError() {
        OrganizationalTreeNodeDTO owner = leaf(2, "Bob Mizrahi", "Owner", false);
        assertDoesNotThrow(() -> new OrgTreeRenderer(owner));
    }

    @Test
    void managerLeaf_constructsWithoutError() {
        OrganizationalTreeNodeDTO manager = leaf(3, "Carol Levy", "Manager", false);
        assertDoesNotThrow(() -> new OrgTreeRenderer(manager));
    }

    @Test
    void compositeOwner_constructsWithoutError() {
        OrganizationalTreeNodeDTO carol = leaf(3, "Carol Levy", "Manager", false);
        OrganizationalTreeNodeDTO bob   = node(2, "Bob Mizrahi", "Owner", false, List.of(), List.of(carol));
        assertDoesNotThrow(() -> new OrgTreeRenderer(bob));
    }

    @Test
    void deepTree_constructsWithoutError() {
        OrganizationalTreeNodeDTO carol = leaf(3, "Carol Levy",  "Manager", false);
        OrganizationalTreeNodeDTO dave  = leaf(4, "Dave Peretz", "Manager", false);
        OrganizationalTreeNodeDTO bob   = node(2, "Bob Mizrahi", "Owner", false, List.of(), List.of(carol, dave));
        OrganizationalTreeNodeDTO frank = leaf(6, "Frank Tal",   "Manager", false);
        OrganizationalTreeNodeDTO eve   = node(5, "Eve Bar",     "Owner",   false, List.of(), List.of(frank));
        OrganizationalTreeNodeDTO alice = node(1, "Alice Cohen", "Owner",   true,  List.of(), List.of(bob, eve));
        assertDoesNotThrow(() -> new OrgTreeRenderer(alice));
    }

    // ── CSS structure ──────────────────────────────────────────────────────────

    @Test
    void content_hasOrgChartClass() {
        var renderer = new OrgTreeRenderer(leaf(1, "Alice Cohen", "Owner", true));
        String classes = renderer.getElement().getAttribute("class");
        assertNotNull(classes, "class attribute must not be null");
        assertTrue(classes.contains("org-chart"),
                "wrapper div must carry 'org-chart' so CSS connector-line selectors fire");
    }

    // ── variant mapping ────────────────────────────────────────────────────────

    @Test
    void founder_variantIsFounder() {
        var renderer = new OrgTreeRenderer(leaf(1, "Alice Cohen", "Owner", true));
        String html = renderer.getElement().getOuterHTML();
        assertTrue(html.contains("oc-founder"), "founder node must carry 'oc-founder' variant class");
    }

    @Test
    void owner_variantIsOwner() {
        var renderer = new OrgTreeRenderer(leaf(2, "Bob Mizrahi", "Owner", false));
        String html = renderer.getElement().getOuterHTML();
        assertTrue(html.contains("oc-owner"), "owner node must carry 'oc-owner' variant class");
    }

    @Test
    void manager_variantIsManager() {
        var renderer = new OrgTreeRenderer(leaf(3, "Carol Levy", "Manager", false));
        String html = renderer.getElement().getOuterHTML();
        assertTrue(html.contains("oc-manager"), "manager node must carry 'oc-manager' variant class");
    }

    // ── permissions displayed in sub ───────────────────────────────────────────

    @Test
    void managerWithPermissions_subTextContainsPermissionNames() {
        OrganizationalTreeNodeDTO manager = new OrganizationalTreeNodeDTO(
                3, "Carol Levy", "Manager", false,
                List.of(Permission.MANAGE_INVENTORY, Permission.VIEW_SALES), new ArrayList<>());
        var renderer = new OrgTreeRenderer(manager);
        String html = renderer.getElement().getOuterHTML();
        assertTrue(html.contains("MANAGE_INVENTORY"), "sub text must list granted permissions");
        assertTrue(html.contains("VIEW_SALES"),        "sub text must list granted permissions");
    }

    @Test
    void owner_doesNotRenderSubTextBlock() {
        OrganizationalTreeNodeDTO owner = new OrganizationalTreeNodeDTO(
                2, "Bob Mizrahi", "Owner", false, List.of(), new ArrayList<>());
        var renderer = new OrgTreeRenderer(owner);
        assertFalse(renderer.getElement().getOuterHTML().contains("oc-sub"),
                "owner node must not render a sub-text block regardless of permissions list");
    }
}
