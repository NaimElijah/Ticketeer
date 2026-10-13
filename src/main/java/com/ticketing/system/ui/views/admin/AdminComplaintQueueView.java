package com.ticketing.system.ui.views.admin;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.messaging.MdConvRow;
import com.ticketing.system.ui.components.messaging.MdThread;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.PermitAll;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * System-admin complaint queue (#269): lists the platform-wide member complaints, lets an admin
 * read each thread, reply, and mark a complaint resolved — all backed by
 * {@link AdminComplaintQueuePresenter} → {@code MessagingService}.
 *
 * <p>Both reply and "Mark resolved" go through {@code respondToComplaint}: a reply auto-flips the
 * status to Responded; resolve sends a short closing note with status Resolved (the domain forbids
 * blank message bodies, so resolve can't be message-less).
 *
 * <p>Third wired master/detail messaging inbox after {@code SupportInboxView} (#277) and
 * {@code CompanyInquiryInboxView} (#268).
 */
@Route(value = "admin/complaints", layout = PlatformAdminLayout.class)
@PageTitle("Complaint Queue · Admin")
@PermitAll
@RequireCapability(Capability.MANAGE_COMPLAINTS)
public class AdminComplaintQueueView extends LkPage {

    private static final String ALL = "All";
    private static final List<String> STATUS_OPTIONS = List.of(ALL, "Open", "Resolved");

    private final AdminComplaintQueuePresenter presenter;

    /** Holds the filter row + master/detail split, or an empty/error banner, so it can be
     *  rebuilt in place without disturbing the page header. */
    private final Div content = new Div();

    private List<ConversationDTO> complaints = List.of();
    private final List<MdConvRow> convRows = new ArrayList<>();
    private String selectedId;
    private String statusFilter = ALL;   // status group (All/Open/Resolved); re-queried on change
    private LkCard detailCard;

    public AdminComplaintQueueView(AdminComplaintQueuePresenter presenter) {
        this.presenter = presenter;

        title("Complaint Queue");
        subtitle("Member complaints opened via Conversation type=COMPLAINT.");
        add(content);
        reload();
    }

    // -- Loading / rendering --------------------------------------------------

    private void reload() {
        switch (presenter.load(AuthSession.token(), statusFilter)) {
            case AdminComplaintQueuePresenter.Outcome.Success ok -> renderQueue(ok.complaints());
            case AdminComplaintQueuePresenter.Outcome.NotAuthenticated ignored -> showBanner(
                "Your session has expired — please sign in again.");
            case AdminComplaintQueuePresenter.Outcome.Failure fail -> showBanner(
                Lk.withReason("Could not load the complaint queue", fail.reason()));
        }
    }

    private void renderQueue(List<ConversationDTO> list) {
        this.complaints = list;
        content.removeAll();
        content.add(buildFilters());

        if (list.isEmpty()) {
            content.add(banner(ALL.equals(statusFilter)
                ? "No complaints in the queue yet."
                : "No complaints match this filter."));
            return;
        }

        // Keep the prior selection if it's still present, else select the first (newest).
        int selIdx = indexOf(selectedId);
        if (selIdx < 0) selIdx = 0;
        selectedId = list.get(selIdx).conversationId();

        Div split = new Div();
        split.addClassName("md-split");
        split.add(buildConversationList(), buildDetailCard());
        content.add(split);
    }

    private Component buildFilters() {
        LkRow row = new LkRow().gap(8);
        // Three coarse groups: All / Open (OPEN+RESPONDED) / Resolved (RESOLVED+CLOSED). The presenter
        // fetches the whole queue and groups in-memory, so each option shows only its complaints.
        LkSelect status = new LkSelect(statusFilter, STATUS_OPTIONS).label("Status");
        status.onChange(v -> {
            if (statusFilter.equals(v)) return;
            statusFilter = v;
            reload();
        });
        row.add(status);
        return row;
    }

    private Component buildConversationList() {
        LkCard card = new LkCard("Complaints").pad(8);
        convRows.clear();
        for (ConversationDTO c : complaints) {
            MdConvRow row = new MdConvRow("warning", c.subject(), c.initiatorDisplayName(),
                relativeTime(c.lastMessageAt()), unreadBadge(c.unreadCountForViewer()));
            if (c.conversationId().equals(selectedId)) row.active();
            row.onSelect(() -> selectById(c.conversationId()));
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
        // The thread shows the member's full complaint description (its first message) and the
        // admin's response once resolved.
        detailCard.add(new MdThread(toThreadMessages(conv)));

        // Complaints are one-shot: a non-terminal complaint gets a single "Resolve" action that
        // opens the dedicated respond page (no inline back-and-forth).
        if (!isTerminal(conv.status())) {
            Div actionRow = new Div();
            actionRow.addClassName("md-detail-actions");
            actionRow.add(new LkBtn("Resolve").variant(LkBtn.Variant.primary)
                .icon(new LkIcon("arrowRight", 15))
                .onClick(e -> navigateToRespond()));
            detailCard.add(actionRow);
        }
    }

    private void navigateToRespond() {
        if (selectedId == null) return;
        UI.getCurrent().navigate(AdminComplaintRespondView.class,
            new RouteParameters("id", selectedId));
    }

    private void showBanner(String message) {
        this.complaints = List.of();
        content.removeAll();
        content.add(banner(message));
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    // -- Lookups --------------------------------------------------------------

    private int indexOf(String conversationId) {
        if (conversationId == null) return -1;
        for (int i = 0; i < complaints.size(); i++) {
            if (complaints.get(i).conversationId().equals(conversationId)) return i;
        }
        return -1;
    }

    private ConversationDTO byId(String conversationId) {
        int idx = indexOf(conversationId);
        return idx < 0 ? null : complaints.get(idx);
    }

    // -- Display mapping ------------------------------------------------------

    /** From the admin's vantage point, ADMIN messages are "You"; the other side is the member. */
    private static List<MdThread.Message> toThreadMessages(ConversationDTO conv) {
        List<MdThread.Message> out = new ArrayList<>();
        for (MessageDTO m : conv.messages()) {
            boolean me = "ADMIN".equals(m.senderType());
            String from = me ? "You" : conv.initiatorDisplayName();
            out.add(new MdThread.Message(from, relativeTime(m.sentAt()), me, m.body()));
        }
        return out;
    }

    private static String unreadBadge(int unreadCount) {
        return unreadCount > 0 ? String.valueOf(unreadCount) : null;
    }

    private static boolean isTerminal(String status) {
        return "RESOLVED".equals(status) || "CLOSED".equals(status);
    }

    /** "OPEN" → "Open", "RESPONDED" → "Responded", etc. */
    private static String humanizeStatus(String status) {
        if (status == null || status.isEmpty()) return "";
        return status.charAt(0) + status.substring(1).toLowerCase();
    }

    /** Compact relative label from a timestamp: now / Nm / Nh / Nd / Nw. */
    private static String relativeTime(LocalDateTime when) {
        if (when == null) return "";
        Duration d = Duration.between(when, LocalDateTime.now());
        long mins = d.toMinutes();
        if (mins < 1) return "now";
        if (mins < 60) return mins + "m";
        long hours = d.toHours();
        if (hours < 24) return hours + "h";
        long days = d.toDays();
        if (days < 7) return days + "d";
        return (days / 7) + "w";
    }
}
