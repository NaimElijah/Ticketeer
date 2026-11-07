package com.ticketing.system.ui.views.admin;

import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkChip;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkFieldRules;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRemoveButton;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter.SentOutreach;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.DisplayLocale;
import com.ticketing.system.ui.support.DisplayTime;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
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
 * picks recipients three ways, in one "Recipients" radio group: chosen members (search-and-add,
 * shown as chips), "All members", or "All producers". Each recipient receives a two-way DIRECT
 * conversation in their Support Inbox.
 * Backed by {@link AdminSendMessagesPresenter} → {@code MessagingService}.
 */
@Route(value = "admin/messages", layout = PlatformAdminLayout.class)
@PageTitle("Send Messages · Admin")
@PermitAll
@RequireCapability(Capability.BROADCAST_ANNOUNCEMENT)
public class AdminSendMessagesView extends LkPage {

    private static final DateTimeFormatter SENT_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", DisplayLocale.PAGE);
    private static final String NO_RECIPIENT = "Add at least one recipient, or pick all members / all producers.";
    private static final String RECIPIENTS = "Recipients";
    private static final String SEARCH_MEMBERS = "Search members";
    private static final String ENTER_SUBJECT = "Enter a subject.";
    private static final String WRITE_MESSAGE = "Write the message.";

    private final AdminSendMessagesPresenter presenter;
    private final LkFormErrors errors = new LkFormErrors();
    private final DisplayTime displayTime;

    private final TextField subject = new TextField("Subject");
    private final TextArea body = new TextArea("Body");
    private final TextField recipientSearch = new TextField(SEARCH_MEMBERS);
    private final Div searchResults = new Div();
    private final Div recipientChips = new Div();
    /** Says how many members a search found and which recipient was added or removed (#188). */
    private final Span recipientStatus = Lk.asStatus(new Span());
    /** Who gets the message: the members chosen below, all members, or all producers. */
    private final RadioButtonGroup<Audience> recipientMode = new RadioButtonGroup<>();

    /** Selected individual recipients: memberId → username (insertion-ordered). */
    private final Map<Integer, String> recipients = new LinkedHashMap<>();
    /** The chips' remove buttons, in recipient order, as the last rebuild made them. */
    private final List<LkRemoveButton> chipRemoveButtons = new ArrayList<>();

    /** History grid lives in its own slot so it can reload in place after a send. */
    private final Div historySlot = new Div();

    public AdminSendMessagesView(AdminSendMessagesPresenter presenter, DisplayTime displayTime) {
        this.presenter = presenter;
        this.displayTime = displayTime;

        // Named as the navigation names it, so the page a user opened says it is that page (#188, WCAG 2.4.6).
        title("Send Messages");
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
        subject.setRequired(true);
        LkFieldRules.sayWhenEmpty(subject, ENTER_SUBJECT);
        subject.setWidthFull();
        LkTextLimit.apply(subject);
        body.setPlaceholder("Body of the message…");
        body.setRequired(true);
        LkFieldRules.sayWhenEmpty(body, WRITE_MESSAGE);
        body.setHeight("160px");
        body.setWidthFull();
        LkTextLimit.apply(body);

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

        // The three choices exclude each other, so they are one radio group rather than two checkboxes that
        // cleared each other (#188, WCAG 1.3.1, 4.1.2). It comes first: it decides whether the search applies.
        // The group alone is "Recipients"; the search below is named for what it does (#188, WCAG 2.4.6).
        recipientMode.setLabel(RECIPIENTS);
        recipientMode.setItems(Audience.values());
        recipientMode.setItemLabelGenerator(Audience::label);
        recipientMode.setValue(Audience.CHOSEN_MEMBERS);
        recipientMode.addValueChangeListener(e -> updateRecipientMode());

        // Results and chips are rebuilt as the admin types and picks, so a status that outlives them says what
        // changed; heard, not shown, since both lists show it (#188, WCAG 4.1.3).
        recipientStatus.addClassName(Lk.VISUALLY_HIDDEN);

        LkCol col = new LkCol().gap(14);
        col.add(subject, body, recipientMode, recipientSearch, recipientStatus, searchResults, recipientChips);
        card.add(col);

        LkRow actions = new LkRow().gap(8).justify("flex-end");
        actions.add(new LkBtn("Send Message").variant(LkBtn.Variant.primary)
                .icon(new LkIcon("arrowRight", 15))
                .onClick(e -> send()));
        card.add(actions);
        return card;
    }

    /** While "All members" or "All producers" is chosen, the search bar is greyed out and cleared. */
    private void updateRecipientMode() {
        boolean bulk = isBulk();
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
            // No results show, so a count left from the last search would be false.
            recipientStatus.setText("");
            return;
        }
        switch (presenter.searchUsers(AuthSession.token(), query)) {
            case AdminSendMessagesPresenter.SearchOutcome.Success ok -> renderResults(ok.results());
            case AdminSendMessagesPresenter.SearchOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminSendMessagesPresenter.SearchOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not search members", fail.reason()));
        }
    }

    private void renderResults(List<MemberSearchResultDTO> results) {
        searchResults.removeAll();
        int shown = 0;
        for (MemberSearchResultDTO r : results) {
            if (recipients.containsKey(r.memberId())) continue;
            NativeButton row = new NativeButton(r.username());
            row.addClassName("lk-search-res");
            row.addClickListener(e -> {
                recipients.put(r.memberId(), r.username());
                recipientSearch.clear();
                searchResults.removeAll();
                renderChips();
                recipientStatus.setText(r.username() + " added as a recipient");
                // The result pressed is gone with the results, and the next step is another search (#188).
                recipientSearch.focus();
            });
            searchResults.add(row);
            shown++;
        }
        recipientStatus.setText(shown == 0 ? "No members found"
            : shown + (shown == 1 ? " member found" : " members found"));
    }

    private void renderChips() {
        recipientChips.removeAll();
        chipRemoveButtons.clear();
        for (Map.Entry<Integer, String> entry : recipients.entrySet()) {
            int id = entry.getKey();
            String username = entry.getValue();
            LkChip chip = new LkChip(username);
            LkRemoveButton remove = new LkRemoveButton(username, () -> {
                int position = new ArrayList<>(recipients.keySet()).indexOf(id);
                recipients.remove(id);
                renderChips();
                recipientStatus.setText(username + " removed from the recipients");
                focusAfterRemove(position);
            });
            remove.getStyle().set("margin-left", "6px").set("font-weight", "700");
            chip.add(remove);
            chipRemoveButtons.add(remove);
            recipientChips.add(chip);
        }
    }

    // The rebuild removed the chip whose remove button had focus: focus the one now at its place, the next
    // recipient's, or the last one's when the last went; with none left, the search to add one (#188, WCAG 2.4.3).
    private void focusAfterRemove(int position) {
        if (!chipRemoveButtons.isEmpty()) {
            chipRemoveButtons.get(Math.min(Math.max(position, 0), chipRemoveButtons.size() - 1)).focus();
        } else {
            recipientSearch.focus();
        }
    }

    private void send() {
        errors.clear();
        if (subject.isEmpty()) {
            errors.reject(subject, ENTER_SUBJECT);
        }
        if (body.isEmpty()) {
            errors.reject(body, WRITE_MESSAGE);
        }
        if (!errors.isEmpty()) {
            refuse("Subject and body are required.");
            return;
        }
        if (!isBulk() && recipients.isEmpty()) {
            errors.reject(recipientSearch, NO_RECIPIENT);
            refuse(NO_RECIPIENT);
            return;
        }
        List<Integer> ids = new ArrayList<>(recipients.keySet());
        Audience audience = recipientMode.getValue();
        switch (presenter.send(AuthSession.token(), subject.getValue(), body.getValue(),
                ids, audience == Audience.ALL_MEMBERS, audience == Audience.ALL_PRODUCERS)) {
            case AdminSendMessagesPresenter.ActionOutcome.Success ok -> {
                Toasts.success("Sent to " + String.format("%,d", ok.recipientCount()) + " recipient(s).");
                clearComposer();
                reloadHistory();
            }
            case AdminSendMessagesPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminSendMessagesPresenter.ActionOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not send the message", fail.reason()));
        }
    }

    // The toast stays, but the refused fields now carry the message too, and the first takes focus (#188).
    private void refuse(String toast) {
        Toasts.failure(toast);
        errors.focusFirst();
    }

    private void clearComposer() {
        subject.clear();
        body.clear();
        // A required field's own check marks it when code empties it; after a send there is nothing to correct.
        subject.setInvalid(false);
        body.setInvalid(false);
        recipientSearch.clear();
        searchResults.removeAll();
        recipients.clear();
        renderChips();
        recipientMode.setValue(Audience.CHOSEN_MEMBERS);
        recipientSearch.setEnabled(true);
    }

    // No choice counts as the chosen members, the group's default: then the search applies and a send
    // without recipients is refused, rather than broadcast.
    private boolean isBulk() {
        Audience audience = recipientMode.getValue();
        return audience != null && audience != Audience.CHOSEN_MEMBERS;
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
            row.put("sent", a.sentAt() == null ? "—" : displayTime.format(a.sentAt(), SENT_FMT));
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

    /** Who an outreach goes to, one per radio choice; {@link #label()} is what the choice shows. */
    private enum Audience {
        CHOSEN_MEMBERS("Chosen members"),
        ALL_MEMBERS("All members"),
        ALL_PRODUCERS("All producers");

        private final String label;

        Audience(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
