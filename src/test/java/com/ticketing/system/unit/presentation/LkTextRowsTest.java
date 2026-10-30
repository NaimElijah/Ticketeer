package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkTextRows;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Unit tests for {@link LkTextRows} — the repeatable add-row text input behind the
 * event-creation "Artists" field. Constructed headless (no UI), like {@code VaadinSmokeTest}.
 */
class LkTextRowsTest {

    private static final int ARTIST_LIMIT = 40;

    @Test
    void newComponent_startsWithOneEmptyRow_noValues() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");

        assertEquals(List.of(), rows.getValues());
        assertEquals(1, textFieldCount(rows), "should start with a single empty row");
    }

    @Test
    void setValues_roundTripsInOrder_oneRowEach() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");

        rows.setValues(List.of("The Beatles", "Pink Floyd", "Queen"));

        assertEquals(List.of("The Beatles", "Pink Floyd", "Queen"), rows.getValues());
        assertEquals(3, textFieldCount(rows));
    }

    @Test
    void getValues_trimsAndDropsBlanks() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");

        rows.setValues(List.of("The Beatles", "   ", " Pink Floyd ", "", "  Queen"));

        // Mirrors the old comma-split parser: trim each, drop blanks, preserve order.
        assertEquals(List.of("The Beatles", "Pink Floyd", "Queen"), rows.getValues());
    }

    @Test
    void setValues_emptyOrNull_keepsOneEmptyRow_whenEditable() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");

        rows.setValues(List.of());
        assertEquals(List.of(), rows.getValues());
        assertEquals(1, textFieldCount(rows));

        rows.setValues(null);
        assertEquals(List.of(), rows.getValues());
        assertEquals(1, textFieldCount(rows));
    }

    @Test
    void editable_showsAddButtonAndRemoveControls() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");
        rows.setValues(List.of("A", "B"));

        assertTrue(addButtonVisible(rows), "add button visible while editable");
        assertEquals(2, removeControlCount(rows), "one ✕ remove control per row");
    }

    @Test
    void readOnly_hidesAddButtonAndRemoveControls_butStillReportsValues() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");
        rows.setValues(List.of("A", "B"));

        rows.readOnly(true);

        assertFalse(addButtonVisible(rows), "add button hidden in read-only mode");
        assertEquals(0, removeControlCount(rows), "no ✕ remove controls in read-only mode");
        assertEquals(List.of("A", "B"), rows.getValues(), "values still readable when read-only");
    }

    // The artists' column is bounded (#186). Every row, including ones set later, tells the user
    // about the limit and flags text over it, instead of cutting a paste short with maxlength.
    @Test
    void GivenATextLimit_WhenRowsAreSet_ThenEveryRowNamesTheLimitAndFlagsTextOverIt() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist").textLimit(ARTIST_LIMIT);

        rows.setValues(List.of("A", "x".repeat(ARTIST_LIMIT + 1)));

        List<TextField> fields = descendantsOf(rows, TextField.class);
        assertEquals(2, fields.size());
        fields.forEach(field ->
                assertFalse(field.getElement().hasProperty("maxlength"), "a row must not truncate input"));
        assertEquals("Up to " + ARTIST_LIMIT + " characters", fields.get(0).getHelperText());
        assertEquals("Up to " + ARTIST_LIMIT + " characters; this has " + (ARTIST_LIMIT + 1),
                fields.get(1).getHelperText());
        assertFalse(fields.get(0).isInvalid());
        assertTrue(fields.get(1).isInvalid());
        assertEquals("Too long: at most 40 characters.", fields.get(1).getErrorMessage());
    }

    @Test
    void GivenARowOverTheLimit_WhenTheTextLimitIsSet_ThenTheRowIsFlaggedAtOnce() {
        LkTextRows rows = new LkTextRows("Artists", "+ Add artist");
        rows.setValues(List.of("x".repeat(ARTIST_LIMIT + 1)));

        rows.textLimit(ARTIST_LIMIT);

        List<TextField> fields = descendantsOf(rows, TextField.class);
        assertEquals(1, fields.size());
        assertTrue(fields.get(0).isInvalid());
        assertEquals("Up to " + ARTIST_LIMIT + " characters; this has " + (ARTIST_LIMIT + 1), fields.get(0).getHelperText());
    }

    // -- helpers: walk the component subtree (no UI/rendering needed) -----

    private static int textFieldCount(Component root) {
        return descendantsOf(root, TextField.class).size();
    }

    private static int removeControlCount(Component root) {
        return (int) descendantsOf(root, Span.class).stream()
                .filter(s -> "✕".equals(s.getText()))
                .count();
    }

    private static boolean addButtonVisible(Component root) {
        return descendantsOf(root, LkBtn.class).stream().anyMatch(Component::isVisible);
    }

    private static <T extends Component> List<T> descendantsOf(Component root, Class<T> type) {
        List<T> out = new ArrayList<>();
        root.getChildren().forEach(child -> {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            out.addAll(descendantsOf(child, type));
        });
        return out;
    }
}
