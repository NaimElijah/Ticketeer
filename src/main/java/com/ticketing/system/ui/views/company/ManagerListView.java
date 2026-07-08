package com.ticketing.system.ui.views.company;

import com.ticketing.system.Core.Application.dto.AppointmentInfoDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.company.EditPermissionsDialog;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkConfirm;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkIconBtn;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Route(value = "owner/managers", layout = WorkspaceLayout.class)
@PageTitle("Managers · TicketHub")
@PermitAll
@RequireCapability(Capability.APPOINT_MANAGER)
public class ManagerListView extends LkPage {

    private final ManagerListPresenter presenter;

    /** Roster content lives in its own container so an action can rebuild it
     *  in place ({@link #reload()}) without disturbing the page header/actions. */
    private final LkCol content = new LkCol().gap(0);

    public ManagerListView(ManagerListPresenter presenter) {
        this.presenter = presenter;

        title("Managers");
        subtitle("Roster and permissions for your production company.");
        actions(new LkBtn("Invite Manager")
            .variant(LkBtn.Variant.primary)
            .icon(new LkIcon("plus", 15))
            .onClick(e -> UI.getCurrent().navigate(ManagerInvitationView.class)));

        add(content);
        reload();
    }

    /** (Re)loads the roster into {@link #content}; called on open and after each mutation. */
    private void reload() {
        content.removeAll();
        switch (presenter.loadRoster(AuthSession.token())) {
            case ManagerListPresenter.Outcome.Success ok -> renderRosters(ok);
            case ManagerListPresenter.Outcome.NoCompany ignored -> content.add(banner(
                "You don't own a production company yet. Register one to manage its team."));
            case ManagerListPresenter.Outcome.NotAuthenticated ignored -> content.add(banner(
                "Your session has expired — please sign in again."));
            case ManagerListPresenter.Outcome.Failure fail -> content.add(banner(
                Lk.withReason("Could not load managers", fail.reason()))); 
        }
    }

    private void renderRosters(ManagerListPresenter.Outcome.Success ok) {
        content.add(Lk.h2("Active Managers"));
        content.add(buildActiveCard(ok.activeManagers()));
        content.add(Lk.h2("Pending Invitations"));
        content.add(buildPendingCard(ok.pendingInvitations()));
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    private Component buildActiveCard(List<AppointmentInfoDTO> managers) {
        LkCard card = new LkCard().pad(0);
        LkGrid grid = new LkGrid()
            .col("Name",        "name")
            .col("Role",        "role")
            .col("Permissions", "perms")
            .col("Status",      "status")
            .col("",            "act", LkGrid.Align.RIGHT);

        for (AppointmentInfoDTO m : managers) {
            activeRow(grid, m);
        }
        grid.build();
        card.add(grid);
        return card;
    }

    private void activeRow(LkGrid grid, AppointmentInfoDTO m) {
        Map<String, Object> row = new LinkedHashMap<>();
        // Username is user-controlled — render as text (not innerHTML) so it can't
        // inject markup. Bold via CSS instead of a <b> tag.
        Span n = new Span(m.targetUsername());
        n.getStyle().set("font-weight", "600");
        row.put("name", n);
        row.put("role", new LkBadge(m.role(), LkBadge.Tone.success).small());
        Span p = Lk.muted(permissionLabels(m.grantedPermissions()));
        p.getStyle().set("font-size", "13px");
        row.put("perms", p);
        row.put("status", new LkStatusDot(LkStatusDot.Tone.ok, "Active"));
        LkRow actions = new LkRow().gap(4).noWrap();
        if (Capabilities.has(Capability.EDIT_MANAGER_PERMISSIONS)) {
            LkIconBtn edit = new LkIconBtn(new LkIcon("edit", 15), "Edit permissions");
            edit.addClickListener(e -> new EditPermissionsDialog(
                m.targetUsername(), m.role(), m.grantedPermissions(),
                names -> handleEdit(m, names)).open());
            actions.add(edit);
        }
        if (Capabilities.has(Capability.REVOKE_MANAGER)) {
            LkIconBtn revoke = new LkIconBtn(new LkIcon("trash", 15), "Revoke");
            revoke.addClickListener(e -> new LkConfirm("Revoke manager",
                "Remove " + m.targetUsername() + " from the team? They'll lose all "
                    + "access to this company.",
                LkConfirm.Severity.danger)
                .confirmText("Revoke")
                .prompt()
                .thenAccept(ok -> { if (Boolean.TRUE.equals(ok)) handleRevoke(m); }));
            actions.add(revoke);
        }
        row.put("act", actions);
        grid.row(row);
    }

    private void handleEdit(AppointmentInfoDTO m, List<String> permissionNames) {
        switch (presenter.editPermissions(
                AuthSession.token(), m.companyId(), m.targetUserId(), permissionNames)) {
            case ManagerListPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Permissions updated for " + m.targetUsername() + ".");
                reload();
            }
            case ManagerListPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case ManagerListPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not update permissions — please try again.");
        }
    }

    private void handleRevoke(AppointmentInfoDTO m) {
        switch (presenter.revoke(AuthSession.token(), m.companyId(), m.targetUserId())) {
            case ManagerListPresenter.ActionOutcome.Success ignored -> {
                Toasts.success(m.targetUsername() + " was revoked.");
                reload();
            }
            case ManagerListPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case ManagerListPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not revoke the manager — please try again.");
        }
    }

    private Component buildPendingCard(List<AppointmentInfoDTO> pending) {
        LkCard card = new LkCard().pad(0);
        LkGrid grid = new LkGrid()
            .col("Invitee", "invitee")
            .col("Role",    "role")
            .col("Sent",    "sent")
            .col("Status",  "status");

        for (AppointmentInfoDTO p : pending) {
            pendingRow(grid, p.targetUsername(), p.role(), relativeSent(p.createdAt()));
        }
        grid.build();
        card.add(grid);
        return card;
    }

    private void pendingRow(LkGrid grid, String invitee, String role, String sent) {
        Map<String, Object> row = new LinkedHashMap<>();
        Span inv = Lk.mono(invitee);
        row.put("invitee", inv);
        row.put("role", role);
        row.put("sent", sent);
        row.put("status", new LkStatusDot(LkStatusDot.Tone.warn, "Pending"));
        grid.row(row);
    }

    // -- Display helpers ------------------------------------------------------

    /** Turn enum permission names (MANAGE_INVENTORY) into a readable list (Manage inventory · …). */
    private static String permissionLabels(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "—";
        }
        return permissions.stream()
            .map(ManagerListView::humanize)
            .collect(Collectors.joining(" · "));
    }

    private static String humanize(String enumName) {
        return Arrays.stream(enumName.toLowerCase().split("_"))
            .reduce((a, b) -> a + " " + b)
            .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .orElse(enumName);
    }

    /** Compact "sent" label from the appointment timestamp. */
    private static String relativeSent(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "—";
        }
        long days = Duration.between(createdAt, LocalDateTime.now()).toDays();
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }
}
