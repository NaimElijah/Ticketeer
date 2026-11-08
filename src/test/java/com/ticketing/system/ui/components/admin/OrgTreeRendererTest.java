package com.ticketing.system.ui.components.admin;

import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.domain.Permission;
import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for {@link OrgTreeRenderer}: each member's card says who they are, what they are to the
 * company, what a manager may do, and whether they appointed anyone, whose cards sit nested under theirs.
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

    // ── each card's content ────────────────────────────────────────────────────

    @Test
    void GivenAFounderLeaf_WhenConstructed_ThenTheCardReadsItsNameFounderLeafAndCompanyFounder() {
        var renderer = new OrgTreeRenderer(leaf(1, "Alice Cohen", "Owner", true));

        Element card = card(renderer, "Alice Cohen");

        assertEquals("Founder", part(card, "oc-role"));
        assertEquals("Leaf", part(card, "oc-kind"));
        assertEquals("Company Founder", part(card, "oc-sub"));
        assertEquals("A", part(card, "oc-avatar"), "the avatar shows the name's initial");
    }

    // An owner holds every permission by role, so the card lists none, whatever the DTO carries.
    @Test
    void GivenAnOwnerHoldingPermissions_WhenConstructed_ThenTheCardReadsOwnerAndShowsNoSubText() {
        var renderer = new OrgTreeRenderer(node(2, "bob", "Owner", false,
                List.of(Permission.MANAGE_INVENTORY), List.of()));

        Element card = card(renderer, "bob");

        assertEquals("Owner", part(card, "oc-role"));
        assertEquals("B", part(card, "oc-avatar"), "the initial is upper case");
        assertEquals(Optional.empty(), partIfShown(card, "oc-sub"));
    }

    @Test
    void GivenAManagerWithPermissions_WhenConstructed_ThenTheSubTextListsThemInOrder() {
        var renderer = new OrgTreeRenderer(node(3, "Carol Levy", "Manager", false,
                List.of(Permission.MANAGE_INVENTORY, Permission.VIEW_SALES), List.of()));

        Element card = card(renderer, "Carol Levy");

        assertEquals("Manager", part(card, "oc-role"));
        assertEquals("MANAGE_INVENTORY, VIEW_SALES", part(card, "oc-sub"));
    }

    @Test
    void GivenAManagerWithNoPermissions_WhenConstructed_ThenNoSubText() {
        var renderer = new OrgTreeRenderer(leaf(3, "Carol Levy", "Manager", false));

        assertEquals(Optional.empty(), partIfShown(card(renderer, "Carol Levy"), "oc-sub"));
    }

    // ── the tree's shape ───────────────────────────────────────────────────────

    @Test
    void GivenACompositeOwner_WhenConstructed_ThenItsKindReadsCompositeAndTheChildCardIsNestedUnderIt() {
        OrganizationalTreeNodeDTO carol = leaf(3, "Carol Levy", "Manager", false);
        var renderer = new OrgTreeRenderer(node(2, "Bob Mizrahi", "Owner", false, List.of(), List.of(carol)));

        assertEquals("Composite", part(card(renderer, "Bob Mizrahi"), "oc-kind"));
        assertEquals("Leaf", part(card(renderer, "Carol Levy"), "oc-kind"));
        assertEquals(Optional.of("Bob Mizrahi"), appointerOf(renderer, "Carol Levy"));
        assertEquals(Optional.empty(), appointerOf(renderer, "Bob Mizrahi"));
    }

    @Test
    void GivenADeepTree_WhenConstructed_ThenEachCardIsNestedUnderTheMemberWhoAppointedIt() {
        OrganizationalTreeNodeDTO carol = leaf(3, "Carol Levy",  "Manager", false);
        OrganizationalTreeNodeDTO dave  = leaf(4, "Dave Peretz", "Manager", false);
        OrganizationalTreeNodeDTO bob   = node(2, "Bob Mizrahi", "Owner", false, List.of(), List.of(carol, dave));
        OrganizationalTreeNodeDTO frank = leaf(6, "Frank Tal",   "Manager", false);
        OrganizationalTreeNodeDTO eve   = node(5, "Eve Bar",     "Owner",   false, List.of(), List.of(frank));
        OrganizationalTreeNodeDTO alice = node(1, "Alice Cohen", "Owner",   true,  List.of(), List.of(bob, eve));

        var renderer = new OrgTreeRenderer(alice);

        assertEquals(Optional.of("Alice Cohen"), appointerOf(renderer, "Bob Mizrahi"));
        assertEquals(Optional.of("Alice Cohen"), appointerOf(renderer, "Eve Bar"));
        assertEquals(Optional.of("Bob Mizrahi"), appointerOf(renderer, "Carol Levy"));
        assertEquals(Optional.of("Bob Mizrahi"), appointerOf(renderer, "Dave Peretz"));
        assertEquals(Optional.of("Eve Bar"), appointerOf(renderer, "Frank Tal"));
    }

    // ── CSS structure ──────────────────────────────────────────────────────────

    @Test
    void GivenAFounderLeaf_WhenConstructed_ThenContentHasOrgChartClass() {
        var renderer = new OrgTreeRenderer(leaf(1, "Alice Cohen", "Owner", true));
        String classes = renderer.getElement().getAttribute("class");
        assertNotNull(classes, "class attribute must not be null");
        assertTrue(classes.contains("org-chart"),
                "wrapper div must carry 'org-chart' so CSS connector-line selectors fire");
    }

    // ── variant mapping ────────────────────────────────────────────────────────

    @Test
    void GivenAFounder_WhenConstructed_ThenVariantIsFounder() {
        var renderer = new OrgTreeRenderer(leaf(1, "Alice Cohen", "Owner", true));
        assertTrue(card(renderer, "Alice Cohen").getClassList().contains("oc-founder"),
                "founder node must carry 'oc-founder' variant class");
    }

    @Test
    void GivenAnOwner_WhenConstructed_ThenVariantIsOwner() {
        var renderer = new OrgTreeRenderer(leaf(2, "Bob Mizrahi", "Owner", false));
        assertTrue(card(renderer, "Bob Mizrahi").getClassList().contains("oc-owner"),
                "owner node must carry 'oc-owner' variant class");
    }

    @Test
    void GivenAManager_WhenConstructed_ThenVariantIsManager() {
        var renderer = new OrgTreeRenderer(leaf(3, "Carol Levy", "Manager", false));
        assertTrue(card(renderer, "Carol Levy").getClassList().contains("oc-manager"),
                "manager node must carry 'oc-manager' variant class");
    }

    // ── reading the cards ──────────────────────────────────────────────────────

    private static Stream<Element> descendants(Element element) {
        return Stream.concat(Stream.of(element), element.getChildren().flatMap(OrgTreeRendererTest::descendants));
    }

    private static Optional<Element> firstWithClass(Element root, String className) {
        return descendants(root).filter(e -> e.getClassList().contains(className)).findFirst();
    }

    // The card whose name reads {@code username}.
    private static Element card(OrgTreeRenderer renderer, String username) {
        return descendants(renderer.getElement())
                .filter(e -> e.getClassList().contains("oc-card"))
                .filter(e -> firstWithClass(e, "oc-name").map(Element::getTextRecursively).orElse("").equals(username))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no card named " + username));
    }

    private static Optional<String> partIfShown(Element card, String className) {
        return firstWithClass(card, className).map(Element::getTextRecursively);
    }

    private static String part(Element card, String className) {
        return partIfShown(card, className)
                .orElseThrow(() -> new AssertionError("the card shows no " + className));
    }

    // The name on the card of the list item that holds the list {@code username}'s card sits in, if any.
    private static Optional<String> appointerOf(OrgTreeRenderer renderer, String username) {
        Element item = card(renderer, username).getParent();
        Element list = item.getParent();
        Element outerItem = list.getParent();
        if (!"li".equals(outerItem.getTag())) {
            return Optional.empty();
        }
        return outerItem.getChildren()
                .filter(e -> e.getClassList().contains("oc-card"))
                .findFirst()
                .flatMap(outer -> firstWithClass(outer, "oc-name"))
                .map(Element::getTextRecursively);
    }
}
