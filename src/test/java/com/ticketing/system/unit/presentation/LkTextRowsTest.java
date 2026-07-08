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
