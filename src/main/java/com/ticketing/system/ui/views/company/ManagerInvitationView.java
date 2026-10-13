package com.ticketing.system.ui.views.company;

import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCheckRow;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.ManagerInvitationPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route(value = "owner/managers/invite", layout = WorkspaceLayout.class)
@PageTitle("Invite Manager · TicketHub")
@PermitAll
@RequireCapability(Capability.APPOINT_MANAGER)
public class ManagerInvitationView extends LkPage {

        // Map each display label to its domain Permission value (same order as PERMS list).
    private static final Map<String, Permission> PERM_MAP = new LinkedHashMap<>();
    static {
        PERM_MAP.put("Manage events",          Permission.MANAGE_INVENTORY);
        PERM_MAP.put("View sales history",     Permission.VIEW_SALES);
        PERM_MAP.put("Edit purchase policies", Permission.EDIT_POLICIES);
        PERM_MAP.put("Respond to inquiries",   Permission.RESPOND_TO_INQUIRIES);
        PERM_MAP.put("Manage venue maps",      Permission.CONFIGURE_VENUE);
    }

private final ManagerInvitationPresenter presenter;
    private final TextField invitee = new TextField("Username or email");
    // Keep references so we can read isChecked() on submit.
    private final Map<LkCheckRow, Permission> checkRows = new LinkedHashMap<>();
    public ManagerInvitationView(ManagerInvitationPresenter presenter) {
        this.presenter = presenter;
        title("Invite a Manager");
        subtitle("Grant a member scoped access to manage this company.");
        add(buildForm());
    }
   private Component buildForm() {
        Div narrow = new Div();
        narrow.addClassName("form-narrow");

        LkCard inviteeCard = new LkCard("Invitee").pad(20);
        invitee.setPlaceholder("Who do you want to invite?");
        invitee.setRequired(true);
        invitee.setWidthFull();
        inviteeCard.add(invitee);

        LkCard permsCard = new LkCard("Permissions").pad(20);
        LkCol col = new LkCol().gap(4);
        int i = 0;
        for (Map.Entry<String, Permission> entry : PERM_MAP.entrySet()) {
            LkCheckRow row = new LkCheckRow(entry.getKey(), i < 2);
            checkRows.put(row, entry.getValue());
            col.add(row);
            i++;
        }
        permsCard.add(col);

        LkRow actions = new LkRow().gap(8).justify("flex-end");
        actions.add(
            new LkBtn("Cancel").variant(LkBtn.Variant.tertiary)
                .onClick(e -> UI.getCurrent().navigate(ManagerListView.class)),
            new LkBtn("Send Invitation").variant(LkBtn.Variant.primary)
                .onClick(e -> sendInvitation())
        );

        narrow.add(inviteeCard, permsCard, actions);
        return narrow;
    }

   private void sendInvitation() {
        if (invitee.isEmpty()) {
            Toasts.failure("Enter a username or email to invite.");
            return;
        }
        List<Permission> selected = new ArrayList<>();
        for (Map.Entry<LkCheckRow, Permission> entry : checkRows.entrySet()) {
            if (entry.getKey().isChecked()) selected.add(entry.getValue());
        }
        if (selected.isEmpty()) {
            Toasts.failure("Select at least one permission.");
            return;
        }
        switch (presenter.invite(AuthSession.token(), invitee.getValue(), selected)) {
            case ManagerInvitationPresenter.Outcome.Success ignored -> {
                Toasts.success("Invitation sent to " + invitee.getValue() + ".");
                UI.getCurrent().navigate(ManagerListView.class);
            }
            case ManagerInvitationPresenter.Outcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case ManagerInvitationPresenter.Outcome.NoCompany ignored ->
                Toasts.failure("You don't own a company — register one first.");
            case ManagerInvitationPresenter.Outcome.UserNotFound u ->
                Toasts.failure("User \"" + u.username() + "\" was not found.");
            case ManagerInvitationPresenter.Outcome.Failure fail ->
                Toasts.failure("Could not send the invitation — please try again.");
        }
    }
    

}
