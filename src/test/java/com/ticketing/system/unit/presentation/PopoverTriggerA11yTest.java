package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkDateRangeField;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.popover.Popover;
import com.vaadin.flow.dom.Element;

/**
 * The kit's popover fields open from a real button that says what it is and what it holds, and their
 * options say which is chosen (#188, A-B2, P-M2, U-A5, U-A6, A-m5; WCAG 2.1.1, 4.1.2, 1.3.1): Enter and
 * Space open a trigger, a screen reader announces its label and value, and the chosen option is said, not
 * only shown by colour and a hidden icon.
 *
 * <p>Contract per trigger: a native button (or role=button and focusable), an accessible name holding its
 * label and its current value, and {@code aria-haspopup}. Per option: {@code aria-checked} or
 * {@code aria-selected} true on the chosen one only. Per popover: an accessible name.
 */
class PopoverTriggerA11yTest {

    // ---- F2: popover triggers (#188) ----

    private static final String COUNTRY = "Country";
    private static final List<String> COUNTRIES = List.of("Any country", "Israel", "France");
    private static final String STATUS = "Status";
    private static final List<String> STATUSES = List.of("Open", "Closed", "Pending");
    private static final String WHEN = "When";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- LkSelect ----

    @Test
    void GivenASelect_WhenItRenders_ThenItsTriggerIsAButtonNamedByLabelAndValueThatOpensAPopup() {
        LkSelect select = new LkSelect("Israel", COUNTRIES).label(COUNTRY);

        assertTriggerContract(select, COUNTRY, "Israel");
    }

    @Test
    void GivenASelect_WhenItsValueChanges_ThenTheTriggerIsNamedByTheNewValue() {
        LkSelect select = new LkSelect("Israel", COUNTRIES).label(COUNTRY);

        select.setValue("France");

        assertTrue(A11y.name(trigger(select)).contains("France"),
            "the trigger must announce the new value: " + A11y.describe(trigger(select)));
    }

    @Test
    void GivenASelect_WhenTheUserPicksAnotherOption_ThenOnlyThatOptionIsMarkedChosen() {
        LkSelect select = new LkSelect("Israel", COUNTRIES).label(COUNTRY);
        harness.attach(select);
        assertOnlyChosen(select, COUNTRIES, "Israel");

        choose(select, "France");

        assertOnlyChosen(select, COUNTRIES, "France");
    }

    @Test
    void GivenASelect_WhenItRenders_ThenItsPopoverIsNamed() {
        assertPopoverNamed(new LkSelect("Israel", COUNTRIES).label(COUNTRY), COUNTRY);
    }

    // ---- LkDateRangeField ----

    @Test
    void GivenADateRange_WhenItRenders_ThenItsTriggerIsAButtonNamedByLabelAndValueThatOpensAPopup() {
        LkDateRangeField range = new LkDateRangeField("Next 7 days").label(WHEN);

        assertTriggerContract(range, WHEN, "Next 7 days");
    }

    @Test
    void GivenADateRange_WhenTheUserPicksAnotherPreset_ThenOnlyThatPresetIsMarkedChosen() {
        LkDateRangeField range = new LkDateRangeField("Next 7 days").label(WHEN);
        harness.attach(range);
        List<String> presets = List.of("Any time", "Today", "Next 7 days", "Next 30 days");
        assertOnlyChosen(range, presets, "Next 7 days");

        choose(range, "Today");

        assertOnlyChosen(range, presets, "Today");
        assertTrue(A11y.name(trigger(range)).contains("Today"),
            "the trigger must announce the new value: " + A11y.describe(trigger(range)));
    }

    @Test
    void GivenADateRange_WhenItRenders_ThenItsPopoverIsNamed() {
        assertPopoverNamed(new LkDateRangeField("Any time").label(WHEN), WHEN);
    }

    // ---- LkFilterChip ----

    @Test
    void GivenAFilterChipWithOneOptionApplied_WhenItRenders_ThenItsTriggerIsAButtonNamedByLabelAndChoiceThatOpensAPopup() {
        LkFilterChip chip = new LkFilterChip(STATUS, STATUSES, false, List.of("Open"));

        assertTriggerContract(chip, STATUS, "Open");
    }

    @Test
    void GivenAFilterChip_WhenTheUserTicksAnotherOption_ThenBothTickedOptionsAreMarkedChosen() {
        LkFilterChip chip = new LkFilterChip(STATUS, STATUSES, false, List.of("Open"));
        harness.attach(chip);
        assertOnlyChosen(chip, STATUSES, "Open");

        choose(chip, "Closed");

        assertEquals(List.of("Open", "Closed"), chosen(chip, STATUSES),
            "each ticked option must say so: " + options(chip));
    }

    @Test
    void GivenAFilterChip_WhenItRenders_ThenItsPopoverIsNamed() {
        assertPopoverNamed(new LkFilterChip(STATUS, STATUSES), STATUS);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static void assertTriggerContract(Component field, String label, String value) {
        Element trigger = trigger(field);
        assertTrue(A11y.isButton(trigger),
            "the trigger must be a native button, or role=button and focusable: " + A11y.describe(trigger));
        String name = A11y.name(trigger);
        assertTrue(name.contains(label) && name.contains(value),
            "the trigger must be named by its label \"" + label + "\" and its value \"" + value + "\": "
                + A11y.describe(trigger));
        String popup = trigger.getAttribute("aria-haspopup");
        assertTrue(popup != null && !popup.isBlank() && !"false".equals(popup),
            "the trigger must say it opens a popup (aria-haspopup): " + A11y.describe(trigger));
        List<Popover> popovers = ViewHarness.find(field, Popover.class);
        assertEquals(1, popovers.size(), "the field must have one popover");
        assertEquals(trigger, popovers.get(0).getTarget().getElement(), "the popover must open from the trigger");
        assertTrue(popovers.get(0).isOpenOnClick(), "the trigger's click must open the popover");
    }

    // The trigger is what takes focus outside the popover's overlay.
    private static Element trigger(Component field) {
        List<Element> focusable = A11y.elements(field, e -> A11y.focusable(e) && !inPopover(e));
        assertEquals(1, focusable.size(), "the field must have one focusable trigger outside its popover; it has: "
            + focusable.stream().map(A11y::describe).toList());
        return focusable.get(0);
    }

    private static boolean inPopover(Element e) {
        for (Element a = e; a != null; a = a.getParent()) {
            if ("vaadin-popover".equals(a.getTag())) {
                return true;
            }
        }
        return false;
    }

    private static void assertOnlyChosen(Component field, List<String> labels, String expected) {
        assertEquals(List.of(expected), chosen(field, labels),
            "only \"" + expected + "\" must say it is chosen (aria-checked or aria-selected): " + options(field));
    }

    private static List<String> chosen(Component field, List<String> labels) {
        return labels.stream().filter(label -> isChosen(option(field, label))).toList();
    }

    private static boolean isChosen(Element option) {
        return "true".equals(option.getAttribute("aria-checked")) || "true".equals(option.getAttribute("aria-selected"));
    }

    private static Element option(Component field, String label) {
        return A11y.elements(field, e -> inPopover(e) && !"vaadin-popover".equals(e.getTag())
                && label.equals(A11y.name(e)) && !A11y.role(e).isEmpty())
            .stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("no option \"" + label + "\" in: " + options(field)));
    }

    private static List<String> options(Component field) {
        return A11y.elements(field, e -> inPopover(e) && !A11y.role(e).isEmpty()).stream()
            .map(A11y::describe)
            .toList();
    }

    private static void choose(Component field, String label) {
        Element option = option(field, label);
        ViewHarness.openPopoverHolding(option);
        A11y.activate(option);
    }

    private static void assertPopoverNamed(Component field, String label) {
        List<Popover> popovers = ViewHarness.find(field, Popover.class);
        assertFalse(popovers.isEmpty(), "the field has no popover");
        for (Popover popover : popovers) {
            String name = popover.getAriaLabel()
                .or(() -> popover.getAriaLabelledBy().flatMap(id -> A11y.byId(popover.getElement(), id).map(A11y::text)))
                .orElse("");
            assertTrue(name.toLowerCase(Locale.ROOT).contains(label.toLowerCase(Locale.ROOT)),
                "the popover must be named after its field \"" + label + "\" (setAriaLabel or setAriaLabelledBy); "
                    + "it is named \"" + name + "\"");
        }
    }
}
