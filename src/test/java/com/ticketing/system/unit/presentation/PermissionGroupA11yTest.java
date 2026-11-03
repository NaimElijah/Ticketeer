package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.company.EditPermissionsDialog;
import com.ticketing.system.ui.presenters.company.ManagerInvitationPresenter;
import com.ticketing.system.ui.views.company.ManagerInvitationView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.Element;

/**
 * A manager's permission checkboxes sit in one group named "Permissions", so a screen reader says what
 * the checkboxes decide when focus enters them (#188, P-B2; WCAG 1.3.1).
 */
class PermissionGroupA11yTest {

    // ---- F1: permission lists are named groups (#188) ----

    private static final String GROUP_NAME = "Permissions";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheManagerInvitation_WhenItRenders_ThenItsPermissionCheckboxesSitInOneGroupNamedPermissions() {
        ViewHarness.signInMember();
        ManagerInvitationView view = new ManagerInvitationView(mock(ManagerInvitationPresenter.class));

        assertCheckboxesInOneNamedGroup(view);
    }

    @Test
    void GivenTheEditPermissionsDialog_WhenItOpens_ThenItsPermissionCheckboxesSitInOneGroupNamedPermissions() {
        new EditPermissionsDialog("carol", "Manager", List.of("VIEW_SALES"), names -> { }).open();

        List<Component> dialogs = harness.dialogs();
        assertEquals(1, dialogs.size(), "the dialog must open");
        assertCheckboxesInOneNamedGroup(dialogs.get(0));
    }

    private static void assertCheckboxesInOneNamedGroup(Component root) {
        List<Element> boxes = A11y.elements(root, e -> "checkbox".equals(A11y.role(e)));
        assertFalse(boxes.isEmpty(), "the permissions must render as checkboxes");
        List<Element> groups = A11y.elements(root, e -> "group".equals(A11y.role(e)));
        assertEquals(1, groups.size(), "the permissions must sit in one group; groups found: "
            + groups.stream().map(A11y::describe).toList());
        Element group = groups.get(0);
        assertEquals(GROUP_NAME, A11y.name(group), "the group must be named");
        boxes.forEach(box -> assertTrue(A11y.within(box, group),
            "every permission checkbox must sit in the group: " + A11y.describe(box)));
    }
}
