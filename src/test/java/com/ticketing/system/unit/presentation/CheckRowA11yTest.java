package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkCheckRow;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.dom.Element;

/**
 * The kit's check row is a checkbox to a keyboard and a screen reader, not a clickable span (#188, A-B1,
 * P-B2; WCAG 2.1.1, 4.1.2). It decides "All members" / "All producers" in the admin's broadcast and a
 * manager's permissions, so without it a keyboard user cannot broadcast or grant a permission at all.
 *
 * <p>Any of the usual fixes passes: a native {@code input type=checkbox}, a Vaadin {@code Checkbox}, or an
 * element with {@code role=checkbox}, {@code tabindex=0}, {@code aria-checked} and a Space key listener.
 */
class CheckRowA11yTest {

    // ---- F1: LkCheckRow (#188) ----

    private static final String LABEL = "All members";
    private static final Pattern SPACE = Pattern.compile("' '|\" \"|(?i)space");

    @Test
    void GivenACheckRow_WhenItRenders_ThenItHasOneFocusableCheckboxNamedByItsLabel() {
        LkCheckRow row = new LkCheckRow(LABEL, false);

        Element box = checkbox(row);

        assertTrue(A11y.focusable(box), "the checkbox must be reachable with Tab: " + A11y.describe(box));
        assertEquals(LABEL, A11y.name(box), "the checkbox must be named by its label");
    }

    @Test
    void GivenAnUncheckedRow_WhenItRenders_ThenTheCheckboxSaysUnchecked() {
        LkCheckRow row = new LkCheckRow(LABEL, false);

        assertEquals(Optional.of(false), exposedChecked(checkbox(row)));
    }

    @Test
    void GivenACheckedRow_WhenItRenders_ThenTheCheckboxSaysChecked() {
        LkCheckRow row = new LkCheckRow(LABEL, true);

        assertEquals(Optional.of(true), exposedChecked(checkbox(row)));
    }

    @Test
    void GivenAnUncheckedRow_WhenTheUserTogglesItTwice_ThenTheCheckboxFollowsAndTheCallbackGetsEachState() {
        List<Boolean> toggled = new ArrayList<>();
        LkCheckRow row = new LkCheckRow(LABEL, false).onToggle(toggled::add);

        userToggle(row);
        assertEquals(Optional.of(true), exposedChecked(checkbox(row)), "checked after the first toggle");
        userToggle(row);
        assertEquals(Optional.of(false), exposedChecked(checkbox(row)), "unchecked after the second toggle");

        assertEquals(List.of(true, false), toggled);
        assertEquals(false, row.isChecked());
    }

    // The admin view unchecks "All producers" when "All members" is checked, through setChecked.
    @Test
    void GivenACheckedRow_WhenItIsUncheckedInCode_ThenTheCheckboxSaysUnchecked() {
        LkCheckRow row = new LkCheckRow(LABEL, true);

        row.setChecked(false);

        assertEquals(Optional.of(false), exposedChecked(checkbox(row)));
    }

    // A native or Vaadin checkbox toggles on Space in the browser; a role=checkbox element needs a listener.
    @Test
    void GivenACheckRow_WhenSpaceIsPressedOnIt_ThenItToggles() {
        List<Boolean> toggled = new ArrayList<>();
        LkCheckRow row = new LkCheckRow(LABEL, false).onToggle(toggled::add);
        Element box = checkbox(row);
        if (nativeCheckbox(box)) {
            return;
        }

        Element listener = A11y.elements(row).stream()
            .filter(e -> A11y.within(box, e) && A11y.listensForKey(e, SPACE))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "a role=checkbox element must toggle on Space, but nothing around it listens for Space: "
                    + A11y.describe(box)));
        A11y.fire(listener, "keydown");

        assertEquals(List.of(true), toggled, "Space must toggle the row once");
        assertEquals(Optional.of(true), exposedChecked(box));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Element checkbox(LkCheckRow row) {
        List<Element> boxes = A11y.elements(row, e -> "checkbox".equals(A11y.role(e)));
        assertEquals(1, boxes.size(), "the row must render one element with checkbox semantics (a native or "
            + "Vaadin checkbox, or role=checkbox); it renders: "
            + A11y.elements(row).stream().map(A11y::describe).toList());
        return boxes.get(0);
    }

    private static boolean nativeCheckbox(Element box) {
        return "input".equals(box.getTag()) || "vaadin-checkbox".equals(box.getTag());
    }

    // aria-checked for a role=checkbox element; the checked property (or attribute) for a native one.
    private static Optional<Boolean> exposedChecked(Element box) {
        if (!nativeCheckbox(box)) {
            String aria = box.getAttribute("aria-checked");
            return aria == null ? Optional.empty() : Optional.of(Boolean.parseBoolean(aria));
        }
        return Optional.of(box.getProperty("checked", false) || box.hasAttribute("checked"));
    }

    // A click as the browser delivers it: on the checkbox, bubbling up through the row. A Vaadin checkbox
    // reports a click as a change of its value instead.
    @SuppressWarnings("unchecked")
    private static void userToggle(LkCheckRow row) {
        Element box = checkbox(row);
        Optional<Component> component = box.getComponent();
        if (component.isPresent() && component.get() instanceof HasValue<?, ?> field
                && field.getValue() instanceof Boolean on) {
            ((HasValue<?, Boolean>) field).setValue(!on);
            return;
        }
        A11y.fireBubbling(box, row.getElement(), "click");
    }
}
