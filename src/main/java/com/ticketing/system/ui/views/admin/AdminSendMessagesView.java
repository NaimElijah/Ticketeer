package com.ticketing.system.ui.views.admin;

import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCheckRow;
import com.ticketing.system.ui.components.kit.LkChip;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter.SentOutreach;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin proactive outreach (II.6.3.2 — "Send Messages"). The admin composes a subject + body and
 * picks recipients three ways: search-and-add individual members (chips), "All members", or
 * "All producers". Each recipient receives a two-way DIRECT conversation in their Support Inbox.
 * Backed by {@link AdminSendMessagesPresenter} → {@code MessagingService}.
 */
@Route(value = "admin/messages", layout = PlatformAdminLayout.class)
@PageTitle("Send Messages · Admin")
@PermitAll
@RequireCapability(Capability.BROADCAST_ANNOUNCEMENT)
public class AdminSendMessagesView extends LkPage {

    private static final DateTimeFormatter SENT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm");

    private final AdminSendMessagesPresenter presenter;

    private final TextField subject = new TextField("Subject");
    private final TextArea body = new TextArea("Body");
    private final TextField recipientSearch = new TextField("Recipients");
    private final Div searchResults = new Div();
    private final Div recipientChips = new Div();
    private final LkCheckRow allMembers = new LkCheckRow("All members", false);
    private final LkCheckRow allProducers = new LkCheckRow("All producers", false);

    /** Selected individual recipients: memberId → username (insertion-ordered). */
    private final Map<Integer, String> recipients = new LinkedHashMap<>();

    /** History grid lives in its own slot so it can reload in place after a send. */
    private final Div historySlot = new Div();

    public AdminSendMessagesView(AdminSendMessagesPresenter presenter) {
        this.presenter = presenter;

        title("Outreach System Members");
        subtitle("Send a message to individual members, all members, or all producers.");

        add(buildComposer());
        add(Lk.h2("Conversations Started"));
        add(historySlot);
        reloadHistory();
    }

    // -- Composer -------------------------------------------------------------

    private Component buildComposer() {
        LkCard card = new LkCard("New Message").pad(20);

        subject.setPlaceholder("Short, clear headline");
        subject.setWidthFull();
        body.setPlaceholder("Body of the message…");
        body.setHeight("160px");
        body.setWidthFull();

        recipientSearch.setPlaceholder("Search members by username…");
        recipientSearch.setWidthFull();
        recipientSearch.setClearButtonVisible(true);
        recipientSearch.setValueChangeMode(ValueChangeMode.LAZY);
        recipientSearch.setValueChangeTimeout(200);
        recipientSearch.setPrefixComponent(new LkIcon("search", 16));
        recipientSearch.addValueChangeListener(e -> doSearch(e.getValue()));

        searchResults.addClassName("lk-search-results");
        recipientChips.addClassName("md-recipient-chips");
        recipientChips.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "6px");

        // "All members" and "All producers" are mutually exclusive — checking one clears the other.
        // (LkCheckRow.setChecked does not re-fire onToggle, so this can't loop.)
        allMembers.onToggle(on -> { if (on) allProducers.setChecked(false); updateRecipientMode(); });
        allProducers.onToggle(on -> { if (on) allMembers.setChecked(false); updateRecipientMode(); });

        LkCol col = new LkCol().gap(14);
        col.add(subject, body, recipientSearch, searchResults, recipientChips,
                new LkRow(allMembers, allProducers).gap(16));
        card.add(col);

        LkRow actions = new LkRow().gap(8).justify("flex-end");
        actions.add(new LkBtn("Send Message").variant(LkBtn.Variant.primary)
                .icon(new LkIcon("arrowRight", 15))
                .onClick(e -> send()));
        card.add(actions);
        return card;
    }

    /** When "All members"/"All producers" is checked, the search bar is greyed out and cleared. */
    private void updateRecipientMode() {
        boolean bulk = allMembers.isChecked() || allProducers.isChecked();
        recipientSearch.setEnabled(!bulk);
        if (bulk) {
            recipients.clear();
            searchResults.removeAll();
            recipientSearch.clear();
            renderChips();
        }
    }

    private void doSearch(String query) {
        searchResults.removeAll();
        if (query == null || query.isBlank() || !recipientSearch.isEnabled()) {
            return;
        }
        switch (presenter.searchUsers(AuthSession.token(), query)) {
            case AdminSendMessagesPresenter.SearchOutcome.Success ok -> renderResults(ok.results());
            case AdminSendMessagesPresenter.SearchOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminSendMessagesPresenter.SearchOutcome.Failure fail ->
                Toasts.failure("Could not search members — please try again.");
        }
    }

    private void renderResults(List<MemberSearchResultDTO> results) {
        searchResults.removeAll();
        for (MemberSearchResultDTO r : results) {
            if (recipients.containsKey(r.memberId())) continue;
            NativeButton row = new NativeButton(r.username());
            row.addClassName("lk-search-res");
            row.addClickListener(e -> {
                recipients.put(r.memberId(), r.username());
                recipientSearch.clear();
                searchResults.removeAll();
                renderChips();
            });
            searchResults.add(row);
        }
    }

    private void renderChips() {
        recipientChips.removeAll();
        for (Map.Entry<Integer, String> entry : recipients.entrySet()) {
            int id = entry.getKey();
            LkChip chip = new LkChip(entry.getValue());
            Span remove = new Span("✕");
            remove.getStyle().set("cursor", "pointer").set("margin-left", "6px").set("font-weight", "700");
            remove.getElement().addEventListener("click", e -> {
                recipients.remove(id);
                renderChips();
            });
            chip.add(remove);
            recipientChips.add(chip);
        }
    }

    private void send() {
        if (subject.isEmpty() || body.isEmpty()) {
            Toasts.failure("Subject and body are required.");
            return;
        }
        boolean bulk = allMembers.isChecked() || allProducers.isChecked();
        if (!bulk && recipients.isEmpty()) {
            Toasts.failure("Add at least one recipient, or pick all members / all producers.");
            return;
        }
        List<Integer> ids = new ArrayList<>(recipients.keySet());
        switch (presenter.send(AuthSession.token(), subject.getValue(), body.getValue(),
                ids, allMembers.isChecked(), allProducers.isChecked())) {
            case AdminSendMessagesPresenter.ActionOutcome.Success ok -> {
                Toasts.success("Sent to " + String.format("%,d", ok.recipientCount()) + " recipient(s).");
                clearComposer();
                reloadHistory();
            }
            case AdminSendMessagesPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminSendMessagesPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not send the message — please try again.");
        }
    }

    private void clearComposer() {
        subject.clear();
        body.clear();
        recipientSearch.clear();
        searchResults.removeAll();
        recipients.clear();
        renderChips();
        allMembers.setChecked(false);
        allProducers.setChecked(false);
        recipientSearch.setEnabled(true);
    }

    // -- History --------------------------------------------------------------

    private void reloadHistory() {
        historySlot.removeAll();
        switch (presenter.load(AuthSession.token())) {
            case AdminSendMessagesPresenter.Outcome.Success ok ->
                historySlot.add(buildHistoryCard(ok.history()));
            case AdminSendMessagesPresenter.Outcome.NotAuthenticated ignored -> historySlot.add(
                    banner("Your session has expired — please sign in again."));
            case AdminSendMessagesPresenter.Outcome.Failure fail -> historySlot.add(
                    banner(Lk.withReason("Could not load message history", fail.reason())));        
                }
    }

    private Component buildHistoryCard(List<SentOutreach> history) {
        LkCard card = new LkCard().pad(0);
        if (history.isEmpty()) {
            card.add(Lk.muted("No messages sent yet."));
            return card;
        }

        LkGrid grid = new LkGrid()
                .col("Started", "sent")
                .col("Subject", "subj")
                .col("Recipients", "rec", LkGrid.Align.RIGHT);

        for (SentOutreach a : history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sent", a.sentAt() == null ? "—" : a.sentAt().format(SENT_FMT));
            Span subj = new Span(a.subject() == null ? "" : a.subject());
            subj.getStyle().set("font-weight", "600");
            row.put("subj", subj);
            row.put("rec", String.format("%,d", a.recipientCount()));
            grid.row(row);
        }

        grid.build();
        card.add(grid);
        return card;
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }
}
