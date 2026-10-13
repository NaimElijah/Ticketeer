package com.ticketing.system.ui.components.kit;

import java.util.ArrayList;
import java.util.List;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;

/**
 * A labeled, repeatable single-line text input: a vertical stack of rows, each a
 * {@link TextField} plus a "✕" remove control, with a "+ Add" button that appends a new
 * empty row. Lets a user enter a list of short free-text values (e.g. an event's artists)
 * one-per-row instead of as a single comma-separated field.
 *
 * <p>House-style kit component — reuses {@link LkBtn} and mirrors the add/remove-row idiom
 * from {@code VenueMapEditorView} (keep a state list, {@code removeAll()} then rebuild; a
 * "✕" {@link Span} with {@code event.stopPropagation()}). The backend contract is
 * unchanged: {@link #getValues()} returns a trimmed, blank-free {@code List<String>} in row
 * order (the same shape the old comma-split parser produced).
 */
public class LkTextRows extends Div {

    private final NativeLabel label = new NativeLabel();
    private final Div rowsHolder = new Div();
    private final LkBtn addBtn;
    private final List<TextField> rows = new ArrayList<>();
    private String placeholder = "";
    private boolean readOnly = false;

    public LkTextRows(String labelText, String addButtonLabel) {
        addClassName("lk-text-rows");
        getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "6px");

        label.setText(labelText);
        label.getStyle().set("font-size", "0.875rem").set("font-weight", "500")
                .set("color", "var(--lumo-secondary-text-color, #5f6b7a)");

        rowsHolder.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px");

        addBtn = new LkBtn(addButtonLabel).variant(LkBtn.Variant.secondary).size(LkBtn.Size.s)
                .onClick(e -> addAndFocusRow());
        // Wrap so the button stays content-width and left-aligned inside the flex column.
        Div addWrap = new Div(addBtn);
        addWrap.getStyle().set("display", "flex");

        add(label, rowsHolder, addWrap);

        addRowField("");   // one empty row to start
        rebuild();
    }

    public LkTextRows placeholder(String p) {
        this.placeholder = p == null ? "" : p;
        rows.forEach(t -> t.setPlaceholder(this.placeholder));
        return this;
    }

    /**
     * Replace all rows with the given values (one row per value). When the list is
     * empty/null an editable instance keeps a single empty row; a read-only instance keeps
     * none.
     */
    public LkTextRows setValues(List<String> values) {
        rows.clear();
        if (values != null) {
            for (String v : values) {
                addRowField(v == null ? "" : v);
            }
        }
        if (rows.isEmpty() && !readOnly) {
            addRowField("");
        }
        rebuild();
        return this;
    }

    /** Trimmed, blank-free values in row order (mirrors the old comma-split parser). */
    public List<String> getValues() {
        List<String> out = new ArrayList<>();
        for (TextField f : rows) {
            String v = f.getValue();
            if (v != null && !v.trim().isBlank()) {
                out.add(v.trim());
            }
        }
        return List.copyOf(out);
    }

    /** Read-only display mode: no "+ Add", no "✕", and the rows are not editable. */
    public LkTextRows readOnly(boolean ro) {
        this.readOnly = ro;
        addBtn.setVisible(!ro);
        rows.forEach(f -> f.setReadOnly(ro));
        rebuild();
        return this;
    }

    private void addAndFocusRow() {
        TextField field = addRowField("");
        rebuild();
        field.focus();   // let the user keep typing the next name immediately
    }

    private TextField addRowField(String value) {
        TextField field = new TextField();
        field.setValue(value == null ? "" : value);
        field.setPlaceholder(placeholder);
        field.setWidthFull();
        field.setReadOnly(readOnly);
        rows.add(field);
        return field;
    }

    private void removeRow(TextField field) {
        rows.remove(field);
        if (rows.isEmpty() && !readOnly) {
            addRowField("");   // always leave somewhere to type
        }
        rebuild();
    }

    private void rebuild() {
        rowsHolder.removeAll();
        for (TextField field : rows) {
            Div row = new Div();
            row.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px");
            field.getStyle().set("flex", "1");
            row.add(field);
            if (!readOnly) {
                row.add(removeControl(field));
            }
            rowsHolder.add(row);
        }
    }

    private Component removeControl(TextField field) {
        Span x = new Span("✕");
        x.getStyle().set("cursor", "pointer").set("color", "var(--lk-danger, #c0392b)")
                .set("padding", "0 4px").set("flex", "0 0 auto");
        x.getElement().addEventListener("click", e -> removeRow(field))
                .addEventData("event.stopPropagation()");
        return x;
    }
}
