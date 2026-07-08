package com.ticketing.system.ui.components.company;
import com.ticketing.system.organization.application.service.CompanyManagementService;

import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkCheckRow;
import com.ticketing.system.ui.components.kit.LkCol;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Edit-manager-permissions dialog (V2-CADMIN-03). Shows the manager's name and
 * role, then one toggle per {@link Permission} — pre-checked from their current
 * grant set. Save collects the checked permissions' enum names and hands them to
 * the supplied callback (the view forwards them to
 * {@code CompanyManagementService.editManagerPermissions}).
 *
 * <p>The dialog is fully built in the constructor (no UI context required); only
 * {@link #open()} needs a live UI, so it can be constructed in smoke tests. Save
 * is blocked client-side when nothing is checked, mirroring the backend rule that
 * a Manager must keep at least one permission.
 */
public class EditPermissionsDialog {

    private final Dialog dialog = new Dialog();
    private final Map<Permission, LkCheckRow> rows = new LinkedHashMap<>();

    public EditPermissionsDialog(String managerName,
                                 String role,
                                 List<String> currentPermissionNames,
                                 Consumer<List<String>> onSave) {
        dialog.setHeaderTitle("Edit permissions");
        dialog.setWidth("min(460px, 100vw - 32px)");
        dialog.setMaxWidth("92vw");

        LkCol col = new LkCol().gap(14);

        Span who = new Span();
        who.getElement().setProperty("innerHTML", "<b>" + escape(managerName) + "</b>");
        LkBadge roleBadge = new LkBadge(role, LkBadge.Tone.success).small();
        com.vaadin.flow.component.html.Div header = new com.vaadin.flow.component.html.Div(who, roleBadge);
        header.getStyle().set("display", "flex").set("align-items", "center").set("gap", "10px");
        col.add(header);

        List<String> current = currentPermissionNames == null ? List.of() : currentPermissionNames;
        for (Permission p : Permission.values()) {
            LkCheckRow row = new LkCheckRow(humanize(p.name()), current.contains(p.name()));
            rows.put(p, row);
            col.add(row);
        }

        dialog.add(col);

        Button cancel = new Button("Cancel", e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button save = new Button("Save changes", e -> {
            List<String> selected = new ArrayList<>();
            rows.forEach((perm, row) -> {
                if (row.isChecked()) selected.add(perm.name());
            });
            if (selected.isEmpty()) {
                Toasts.warn("A manager must keep at least one permission.");
                return;
            }
            dialog.close();
            onSave.accept(selected);
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        dialog.getFooter().add(cancel, save);
    }

    public void open() {
        dialog.open();
    }

    /** MANAGE_INVENTORY -> "Manage inventory". */
    private static String humanize(String enumName) {
        return Arrays.stream(enumName.toLowerCase().split("_"))
            .reduce((a, b) -> a + " " + b)
            .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .orElse(enumName);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
