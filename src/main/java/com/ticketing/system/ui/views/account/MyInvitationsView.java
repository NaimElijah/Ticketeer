package com.ticketing.system.ui.views.account;

import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Route(value = "my-invitations", layout = MainLayout.class)
@PageTitle("Invitations · TicketHub")
@PermitAll
public class MyInvitationsView extends LkPage {

    private static final String OWNER_ROLE = "Owner";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final MyInvitationsPresenter presenter;

    /** Invitation content lives in its own container so an action can rebuild it
     *  in place ({@link #reload()}) without disturbing the page header. */
    private final LkCol content = new LkCol().gap(0);

    public MyInvitationsView(MyInvitationsPresenter presenter) {
        this.presenter = presenter;

        title("Invitations");
        subtitle("Accept a role to manage events for a production company.");

        add(content);
        reload();
    }

    /** (Re)loads the member's invitations into {@link #content}; called on open and after actions. */
    private void reload() {
        content.removeAll();
        switch (presenter.load(AuthSession.token())) {
            case MyInvitationsPresenter.Outcome.Success ok -> render(ok);
            case MyInvitationsPresenter.Outcome.NotAuthenticated ignored -> content.add(banner(
                "Your session has expired — please sign in again."));
            case MyInvitationsPresenter.Outcome.Failure fail -> content.add(banner(
                Lk.withReason("Could not load invitations", fail.reason())));
        }
    }

    private void render(MyInvitationsPresenter.Outcome.Success ok) {
        content.add(Lk.h2("Pending Invitations"));
        if (ok.pending().isEmpty()) {
            content.add(banner("You have no pending invitations right now."));
        } else {
            content.add(buildPendingCard(ok.pending()));
        }
        if (!ok.history().isEmpty()) {
            content.add(Lk.h2("History"));
            content.add(buildHistoryCard(ok.history()));
        }
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    private Component buildPendingCard(List<InvitationDTO> pending) {
        LkCard card = new LkCard().pad(0);
        LkGrid grid = new LkGrid()
            .col("Company",     "company")
            .col("Role",        "role")
            .col("Invited by",  "by")
            .col("Permissions", "perms")
            .col("Received",    "recv")
            .col("",            "act",  LkGrid.Align.RIGHT);

        for (InvitationDTO inv : pending) {
            pendingRow(grid, inv);
        }
        grid.build();
        card.add(grid);
        return card;
    }

    private void pendingRow(LkGrid grid, InvitationDTO inv) {
        Map<String, Object> row = new LinkedHashMap<>();
        Span c = new Span();
        c.getElement().setProperty("innerHTML", "<b>" + escape(inv.companyName()) + "</b>");
        row.put("company", c);
        row.put("role", new LkBadge(humanize(inv.role()), roleTone(inv.role())).small());
        row.put("by", inv.fromUsername());
        row.put("perms", Lk.muted(permissionLabels(inv)));
        row.put("recv", relativeSent(inv.sentAt()));

        LkRow actions = new LkRow().gap(6).noWrap();
        actions.add(
            new LkBtn("Accept").variant(LkBtn.Variant.primary).size(LkBtn.Size.s)
                .onClick(e -> handleAccept(inv)),
            new LkBtn("Reject").variant(LkBtn.Variant.tertiary).size(LkBtn.Size.s)
                .onClick(e -> handleDecline(inv))
        );
        row.put("act", actions);
        grid.row(row);
    }

    private Component buildHistoryCard(List<InvitationDTO> history) {
        LkCard card = new LkCard().pad(0);
        LkGrid grid = new LkGrid()
            .col("Company", "company")
            .col("Role",    "role")
            .col("Outcome", "outcome")
            .col("Date",    "date");

        for (InvitationDTO inv : history) {
            historyRow(grid, inv);
        }
        grid.build();
        card.add(grid);
        return card;
    }

    private void historyRow(LkGrid grid, InvitationDTO inv) {
        Map<String, Object> row = new LinkedHashMap<>();
        Span c = new Span();
        c.getElement().setProperty("innerHTML", "<b>" + escape(inv.companyName()) + "</b>");
        row.put("company", c);
        row.put("role", humanize(inv.role()));
        row.put("outcome", new LkStatusDot(outcomeTone(inv.status()), outcomeLabel(inv.status())));
        row.put("date", inv.sentAt() == null ? "—" : DATE.format(inv.sentAt()));
        grid.row(row);
    }

    // -- Actions --------------------------------------------------------------

    private void handleAccept(InvitationDTO inv) {
        switch (presenter.accept(AuthSession.token(), inv.companyId())) {
            case MyInvitationsPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Accepted — you are now a " + humanize(inv.role()).toLowerCase(Locale.ENGLISH)
                    + " at " + inv.companyName() + ".");
                // Accepting an owner invitation flips ownership; re-navigate so MainLayout
                // rebuilds the avatar/persona chrome (and the view re-fetches). Manager
                // accepts only need the local grid refresh.
                if (OWNER_ROLE.equals(inv.role())) {
                    UI.getCurrent().navigate(MyInvitationsView.class);
                } else {
                    reload();
                }
            }
            case MyInvitationsPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case MyInvitationsPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not accept the invitation — please try again.");
        }
    }

    private void handleDecline(InvitationDTO inv) {
        switch (presenter.decline(AuthSession.token(), inv.companyId())) {
            case MyInvitationsPresenter.ActionOutcome.Success ignored -> {
                Toasts.warn("Invitation from " + inv.companyName() + " declined.");
                reload();
            }
            case MyInvitationsPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case MyInvitationsPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not decline the invitation — please try again.");
        }
    }

    // -- Display helpers ------------------------------------------------------

    private static LkBadge.Tone roleTone(String role) {
        return OWNER_ROLE.equals(role) ? LkBadge.Tone.primary : LkBadge.Tone.success;
    }

    /** Permission summary for a row — Owner offers grant full access, Managers a list. */
    private static String permissionLabels(InvitationDTO inv) {
        if (OWNER_ROLE.equals(inv.role())) {
            return "Full company access";
        }
        List<String> perms = inv.permissions();
        if (perms == null || perms.isEmpty()) {
            return "—";
        }
        return perms.stream().map(MyInvitationsView::humanize).collect(Collectors.joining(" · "));
    }

    private static String outcomeLabel(String status) {
        return switch (status) {
            case "ACTIVE" -> "Accepted";
            case "REJECTED" -> "Rejected";
            case "REVOKED" -> "Revoked";
            default -> humanize(status);
        };
    }

    private static LkStatusDot.Tone outcomeTone(String status) {
        return "ACTIVE".equals(status) ? LkStatusDot.Tone.ok : LkStatusDot.Tone.muted;
    }

    private static String humanize(String enumName) {
        return Arrays.stream(enumName.toLowerCase(Locale.ENGLISH).split("_"))
            .reduce((a, b) -> a + " " + b)
            .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .orElse(enumName);
    }

    /** Compact "received" label from the invitation timestamp. */
    private static String relativeSent(LocalDateTime sentAt) {
        if (sentAt == null) {
            return "—";
        }
        long days = Duration.between(sentAt, LocalDateTime.now()).toDays();
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}