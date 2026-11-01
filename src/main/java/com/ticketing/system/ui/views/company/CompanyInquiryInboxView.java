package com.ticketing.system.ui.views.company;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.messaging.MdConvRow;
import com.ticketing.system.ui.components.messaging.MdThread;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.OpenedThreads;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.support.DisplayTime;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Company's member-inquiry inbox (#268): lists the member inquiries addressed to the company,
 * lets an owner/manager read each thread, reply, and close it — all backed by
 * {@link CompanyInquiryInboxPresenter} → {@code MessagingService}.
 *
 * <p>The company is resolved like {@code OwnerDashboardView}: the presenter picks the company id
 * seeded from {@link CurrentCompanies} (else the member's first company), and a topbar selector
 * lets multi-company owners switch. A client-side Status chip filters the loaded threads.
 *
 * <p>Second wired master/detail messaging inbox after {@code SupportInboxView} (#277);
 * {@code AdminComplaintQueueView} (#269) mirrors this pattern.
 */
@Route(value = "owner/inquiries", layout = WorkspaceLayout.class)
@PageTitle("Customer Inquiries · TicketHub")
@PermitAll
@RequireCapability(Capability.RESPOND_INQUIRIES)
public class CompanyInquiryInboxView extends LkPage {

    private static final String ALL = "All";

    private final CompanyInquiryInboxPresenter presenter;
    private final DisplayTime displayTime;

    /** Holds the filter row + master/detail split, or an empty/error banner, so it can be
     *  rebuilt in place without disturbing the page header/actions. */
    private final Div content = new Div();

    private List<ConversationDTO> conversations = List.of();  // all inquiries for the company
    private List<ConversationDTO> visible = List.of();        // after the status filter
    private final List<MdConvRow> convRows = new ArrayList<>();
    private String selectedId;
    // The threads this view opened; the presenter reads it to keep a cleared badge cleared (#187).
    private final OpenedThreads opened = new OpenedThreads();
    private String statusFilter = ALL;
    private Integer companyId;   // the company currently being viewed (kept across reloads)
    private LkCard detailCard;

    public CompanyInquiryInboxView(CompanyInquiryInboxPresenter presenter, DisplayTime displayTime) {
        this.presenter = presenter;
        this.displayTime = displayTime;

        title("Customer Inquiries");
        subtitle("Questions from members about your events and company.");
        add(content);
        reload(CurrentCompanies.currentCompanyId());
    }

    // -- Loading / rendering --------------------------------------------------

    private void reload(Integer companyId) {
        switch (presenter.loadFor(AuthSession.token(), companyId)) {
            case CompanyInquiryInboxPresenter.Outcome.Success ok -> applySuccess(ok);
            case CompanyInquiryInboxPresenter.Outcome.NoCompany ignored -> showBanner(
                "You don't belong to a production company yet. Register one to open its workspace.");
            case CompanyInquiryInboxPresenter.Outcome.NotAuthenticated ignored -> showBanner(
                "Your session has expired — please sign in again.");
            case CompanyInquiryInboxPresenter.Outcome.Failure fail -> showBanner(
                Lk.withReason("Could not load inquiries", fail.reason()));
        }
    }

    private void applySuccess(CompanyInquiryInboxPresenter.Outcome.Success ok) {
        this.conversations = ok.conversations();
        this.companyId = ok.selected().companyId();
        subtitle(ok.selected().name() + " · member inquiries");
        actions(buildActions(ok.companies(), ok.selected()));
        renderInbox();
    }

    /** Rebuilds the filter row + split from the in-memory {@link #conversations} (no re-fetch). */
    private void renderInbox() {
        content.removeAll();
        if (conversations.isEmpty()) {
            content.add(banner("No member inquiries yet — questions from members about your "
                + "events will appear here."));
            return;
        }

        content.add(buildFilters());
        this.visible = applyStatusFilter(conversations);
        if (visible.isEmpty()) {
            content.add(banner("No inquiries match this filter."));
            return;
        }

        // Keep the prior selection if it's still visible, else select the first (newest).
        int selIdx = indexOf(selectedId);
        if (selIdx < 0) selIdx = 0;
        selectedId = visible.get(selIdx).conversationId();

        Div split = new Div();
        split.addClassName("md-split");
        split.add(buildConversationList(), buildDetailCard());
        content.add(split);
    }

    private Component buildFilters() {
        LkRow row = new LkRow().gap(8);
        List<String> applied = ALL.equals(statusFilter) ? List.of() : List.of(statusFilter);
        LkFilterChip status = new LkFilterChip("Status",
            List.of("Open", "Closed"), true, applied);
        status.onApply(() -> {
            Set<String> selected = status.getSelected();
            statusFilter = selected.isEmpty() ? ALL : selected.iterator().next();
            renderInbox();
        });
        row.add(status);
        return row;
    }

    private List<ConversationDTO> applyStatusFilter(List<ConversationDTO> all) {
        if (ALL.equals(statusFilter)) return all;
        // "Closed" = terminal (CLOSED/RESOLVED); "Open" = active (OPEN/RESPONDED — i.e. an inquiry
        // the company has replied to is still "Open", not hidden).
        boolean wantClosed = "Closed".equals(statusFilter);
        return all.stream()
            .filter(c -> isTerminal(c.status()) == wantClosed)
            .toList();
    }

    private Component buildConversationList() {
        LkCard card = new LkCard("Inquiries").pad(8);
        convRows.clear();
        for (ConversationDTO c : visible) {
            MdConvRow row = new MdConvRow("comment", c.subject(), c.initiatorDisplayName(),
                displayTime.ageShort(c.lastMessageAt()), presenter.badge(c, opened).orElse(null));
            if (c.conversationId().equals(selectedId)) row.active();
            row.onSelect(() -> openById(c.conversationId()));
            convRows.add(row);
            card.add(row);
        }
        return card;
    }

    private Component buildDetailCard() {
        detailCard = new LkCard().pad(0);
        renderDetail();
        return detailCard;
    }

    /** Selects a thread by id in response to a row click (toggles rows in place). */
    private void selectById(String conversationId) {
        int idx = indexOf(conversationId);
        if (idx < 0 || conversationId.equals(selectedId)) return;
        selectedId = conversationId;
        for (int i = 0; i < convRows.size(); i++) {
            convRows.get(i).active(i == idx);
        }
        renderDetail();
    }

    private void renderDetail() {
        if (detailCard == null) return;
        detailCard.removeAll();
        ConversationDTO conv = byId(selectedId);
        if (conv == null) return;

        detailCard.title(conv.subject());
        detailCard.subtitle(conv.initiatorDisplayName() + " · " + humanizeStatus(conv.status()));
        detailCard.add(new MdThread(toThreadMessages(conv)));

        // Non-terminal inquiries get a "Respond" action that opens the dedicated chat page where
        // the company replies (and can close the thread).
        if (!isTerminal(conv.status())) {
            Div actionRow = new Div();
            actionRow.addClassName("md-detail-actions");
            actionRow.add(new LkBtn("Respond").variant(LkBtn.Variant.primary)
                .icon(new LkIcon("arrowRight", 15))
                .onClick(e -> navigateToRespond(conv.conversationId())));
            detailCard.add(actionRow);
        }
    }

    /** A row click: the reader opens the thread, so it is shown and marked read. */
    private void openById(String conversationId) {
        selectById(conversationId);
        markOpened(conversationId);
    }

    // Only an explicit open marks a thread read: a mark reads it for the reader's whole side, so the
    // row this view selects on its own is shown but not marked (#187). The presenter decides the badge.
    // A failed open leaves it and tells the reader nothing, since they can do nothing about it;
    // LoggingAspect logs the failure at WARN.
    private void markOpened(String conversationId) {
        int idx = indexOf(conversationId);
        if (idx < 0) return;
        ConversationDTO conv = byId(conversationId);
        presenter.open(AuthSession.token(), conv, opened);
        if (presenter.badge(conv, opened).isEmpty()) convRows.get(idx).clearUnread();
    }

    private void navigateToRespond(String conversationId) {
        UI.getCurrent().navigate(CompanyInquiryRespondView.class,
            new RouteParameters("id", conversationId));
    }

    /** Topbar company selector — shown only when the member acts for more than one company. */
    private Component[] buildActions(List<MyCompanyDTO> companies, MyCompanyDTO selected) {
        if (companies.size() <= 1) {
            return new Component[0];
        }
        // Two companies can share a display name, so key the selector on a label that maps
        // unambiguously back to a single companyId (disambiguating collisions with the id).
        List<String> labels = new ArrayList<>();
        Map<String, Integer> idByLabel = new LinkedHashMap<>();
        String selectedLabel = selected.name();
        for (MyCompanyDTO c : companies) {
            String label = idByLabel.containsKey(c.name()) ? c.name() + " · #" + c.companyId() : c.name();
            labels.add(label);
            idByLabel.put(label, c.companyId());
            if (c.companyId() == selected.companyId()) {
                selectedLabel = label;
            }
        }
        LkSelect selector = new LkSelect(selectedLabel, labels).label("Company");
        selector.onChange(label -> {
            Integer id = idByLabel.get(label);
            if (id != null) {
                reload(id);
            }
        });
        return new Component[] { selector };
    }

    private void showBanner(String message) {
        this.conversations = List.of();
        this.visible = List.of();
        subtitle("");
        actions();
        content.removeAll();
        content.add(banner(message));
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    // -- Lookups --------------------------------------------------------------

    private int indexOf(String conversationId) {
        if (conversationId == null) return -1;
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).conversationId().equals(conversationId)) return i;
        }
        return -1;
    }

    private ConversationDTO byId(String conversationId) {
        int idx = indexOf(conversationId);
        return idx < 0 ? null : visible.get(idx);
    }

    // -- Display mapping ------------------------------------------------------

    /** From the company's vantage point, COMPANY messages are "You"; the other side is the member. */
    private List<MdThread.Message> toThreadMessages(ConversationDTO conv) {
        List<MdThread.Message> out = new ArrayList<>();
        for (MessageDTO m : conv.messages()) {
            boolean me = "COMPANY".equals(m.senderType());
            String from = me ? "You" : conv.initiatorDisplayName();
            out.add(new MdThread.Message(from, displayTime.ageShort(m.sentAt()), me, m.body()));
        }
        return out;
    }

    private static boolean isTerminal(String status) {
        return "RESOLVED".equals(status) || "CLOSED".equals(status);
    }

    /** "OPEN" → "Open", "RESPONDED" → "Responded", etc. */
    private static String humanizeStatus(String status) {
        if (status == null || status.isEmpty()) return "";
        return status.charAt(0) + status.substring(1).toLowerCase();
    }
}
