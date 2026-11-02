package com.ticketing.system.ui.components.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.TextField;

/**
 * A labeled, repeatable single-line text input: a vertical stack of rows, each a
 * {@link TextField} plus a "✕" {@link LkRemoveButton}, with a "+ Add" button that appends a new
 * empty row. Lets a user enter a list of short free-text values (e.g. an event's artists)
 * one-per-row instead of as a single comma-separated field.
 *
 * <p>House-style kit component — reuses {@link LkBtn} and mirrors the add/remove-row idiom
 * from {@code VenueMapEditorView} (keep a state list, {@code removeAll()} then rebuild; an
 * {@link LkRemoveButton} per row, named by the row's position, since its text changes as the
 * user types). Each row's field is named by the label and its position, "Artists 1", renumbered
 * after a remove. The backend contract is
 * unchanged: {@link #getValues()} returns a trimmed, blank-free {@code List<String>} in row
 * order (the same shape the old comma-split parser produced).
 *
 * <p>A form refuses it as it refuses a Vaadin field ({@link LkFormErrors}): its invalid state and
 * error message are its first row's, and {@link #focus()} focuses that row (#188, WCAG 3.3.1).
 */
public class LkTextRows extends Div implements HasValidationProperties {

    // Names a row's remove button: "Artists row 2".
    private static final String ROW_NAME = "%s row %d";
    // Names a row's field: "Artists 2". The label above the rows is tied to no field, and the rows share it.
    private static final String ROW_FIELD_NAME = "%s %d";

    private final NativeLabel label = new NativeLabel();
    private final Div rowsHolder = new Div();
    private final LkBtn addBtn;
    private final List<TextField> rows = new ArrayList<>();
    private String placeholder = "";
    private boolean readOnly = false;
    private Integer textLimit;

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
     * Limits every row, present and future, to {@code max} characters through {@link LkTextLimit}:
     * each row names the limit and flags text over it, without truncating what the user typed.
     *
     * @param max the most characters a row may have; not negative
     */
    public LkTextRows textLimit(int max) {
        this.textLimit = max;
        rows.forEach(t -> LkTextLimit.apply(t, max));
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

    /**
     * Marks the first row invalid, or valid again. Does nothing while there is no row, as in a
     * read-only instance with no values, since there is then nothing to correct.
     */
    @Override
    public void setInvalid(boolean invalid) {
        firstRow().ifPresent(f -> f.setInvalid(invalid));
    }

    /** {@code true} while the first row is marked invalid. */
    @Override
    public boolean isInvalid() {
        return firstRow().map(TextField::isInvalid).orElse(false);
    }

    /** Sets the message the first row shows while it is invalid; does nothing while there is no row. */
    @Override
    public void setErrorMessage(String errorMessage) {
        firstRow().ifPresent(f -> f.setErrorMessage(errorMessage));
    }

    /** The first row's error message; {@code null} while there is no row. */
    @Override
    public String getErrorMessage() {
        return firstRow().map(TextField::getErrorMessage).orElse(null);
    }

    /** Moves focus to the first row; does nothing while there is no row. */
    public void focus() {
        firstRow().ifPresent(TextField::focus);
    }

    private Optional<TextField> firstRow() {
        return rows.stream().findFirst();
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
        if (textLimit != null) {
            LkTextLimit.apply(field, textLimit);
        }
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
        for (int i = 0; i < rows.size(); i++) {
            TextField field = rows.get(i);
            // Named here, on every rebuild, so the names follow the positions after a remove (#188).
            field.setAriaLabel(String.format(Locale.ROOT, ROW_FIELD_NAME, label.getText(), i + 1));
            Div row = new Div();
            row.getStyle().set("display", "flex").set("align-items", "center").set("gap", "8px");
            field.getStyle().set("flex", "1");
            row.add(field);
            if (!readOnly) {
                row.add(removeControl(field, i + 1));
            }
            rowsHolder.add(row);
        }
    }

    private Component removeControl(TextField field, int position) {
        LkRemoveButton x = new LkRemoveButton(String.format(Locale.ROOT, ROW_NAME, label.getText(), position),
                () -> removeRow(field));
        x.getStyle().set("color", "var(--lk-danger, #c0392b)")
                .set("padding", "0 4px").set("flex", "0 0 auto");
        return x;
    }
}
