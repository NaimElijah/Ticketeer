package com.ticketing.system.Presentation.views.account;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.MessageDTO;
import com.ticketing.system.Presentation.components.Toasts;
import com.ticketing.system.Presentation.components.kit.Lk;
import com.ticketing.system.Presentation.components.kit.LkBanner;
import com.ticketing.system.Presentation.components.kit.LkBtn;
import com.ticketing.system.Presentation.components.kit.LkCard;
import com.ticketing.system.Presentation.components.kit.LkIcon;
import com.ticketing.system.Presentation.components.kit.LkPage;
import com.ticketing.system.Presentation.components.messaging.MdConvRow;
import com.ticketing.system.Presentation.components.messaging.MdReplyBar;
import com.ticketing.system.Presentation.components.messaging.MdThread;
import com.ticketing.system.Presentation.layouts.MainLayout;
import com.ticketing.system.Presentation.presenters.messaging.SupportInboxPresenter;
import com.ticketing.system.Presentation.session.AuthSession;
import com.ticketing.system.Presentation.views.messaging.NewInquiryView;
import com.ticketing.system.Presentation.views.messaging.SubmitComplaintView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Member's Support inbox (#277): lists the member's outgoing conversations
 * (complaints + company inquiries) and lets them read each thread and reply,
 * all backed by {@link SupportInboxPresenter} → {@code MessagingService}.
 *
 * <p>Honors the {@code support?c=<conversationId>} focus contract introduced by
 * {@code SubmitComplaintView} (#267): navigating with that query param opens the
 * inbox with the matching conversation selected.
 *
 * <p>This is the first wired master/detail messaging inbox; {@code
 * CompanyInquiryInboxView} (#268) and {@code AdminComplaintQueueView} (#269)
 * mirror this pattern.
 */
@Route(value = "support", layout = MainLayout.class)
@PageTitle("Support · TicketHub")
@PermitAll
public class SupportInboxView extends LkPage implements BeforeEnterObserver {

    private final SupportInboxPresenter presenter;

    /** Holds the master/detail split, or an empty/error banner, so {@link #reload()}
     *  can rebuild it in place without disturbing the page header/actions. */
    private final Div content = new Div();

    private List<ConversationDTO> conversations = List.of();
    private final List<MdConvRow> convRows = new ArrayList<>();
    private String selectedId;
    private String focusId;        // from ?c=, applied once on the next render
    private LkCard detailCard;

    public SupportInboxView(SupportInboxPresenter presenter) {
        this.presenter = presenter;

        title("Support Inbox");
        subtitle("Your conversations with organizers and the TicketHub team.");
        actions(
            new LkBtn("New Inquiry").variant(LkBtn.Variant.secondary)
                .icon(new LkIcon("comment", 15))
                .onClick(e -> UI.getCurrent().navigate(NewInquiryView.class)),
            new LkBtn("Submit Complaint").variant(LkBtn.Variant.primary)
                .icon(new LkIcon("warning", 15))
                .onClick(e -> UI.getCurrent().navigate(SubmitComplaintView.class))
        );

        add(content);
        reload();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        List<String> values = event.getLocation().getQueryParameters()
            .getParameters().getOrDefault("c", List.of());
        focusId = values.isEmpty() ? null : values.get(0);
        // The constructor already loaded + rendered; just refine the selection.
        if (focusId != null && !conversations.isEmpty()) {
            selectById(focusId);
        }
    }

    // -- Loading / rendering --------------------------------------------------

    private void reload() {
        switch (presenter.load(AuthSession.token())) {
            case SupportInboxPresenter.Outcome.Success ok -> renderInbox(ok.conversations());
            case SupportInboxPresenter.Outcome.NotAuthenticated ignored -> showBanner(
                "Your session has expired — please sign in again.");
            case SupportInboxPresenter.Outcome.Failure fail -> showBanner(
                  Lk.withReason("Could not load your conversations", fail.reason()));
        }
    }

    private void renderInbox(List<ConversationDTO> list) {
        this.conversations = list;
        content.removeAll();
        if (list.isEmpty()) {
            content.add(banner("No conversations yet — file a complaint and it'll appear "
                + "in your Support inbox."));
            return;
        }

        // Selection priority: ?c= focus (once), then the previously selected thread, then the first.
        // Closed admin outreach stays selectable so the member can still read it (the reply bar is hidden).
        int selIdx = -1;
        if (focusId != null) {
            selIdx = indexOf(focusId);
            focusId = null;
        }
        if (selIdx < 0) selIdx = indexOf(selectedId);
        if (selIdx < 0) selIdx = 0;
        selectedId = list.get(selIdx).conversationId();

        Div split = new Div();
        split.addClassName("md-split");
        split.add(buildConversationList(), buildDetailCard());
        content.add(split);
    }

    private Component buildConversationList() {
        LkCard card = new LkCard("Conversations").pad(8);
        convRows.clear();
        for (ConversationDTO c : conversations) {
            MdConvRow row = new MdConvRow(iconFor(c.type()), c.subject(), whoFor(c),
                relativeTime(c.lastMessageAt()), unreadBadge(c.unreadCountForViewer()));
            if (c.conversationId().equals(selectedId)) row.active();
            row.onSelect(() -> selectById(c.conversationId()));
            if (isLocked(c)) {
                // Admin closed this outreach: still selectable/readable, just dimmed as a "closed" cue.
                // renderDetail() hides the reply bar for terminal threads, so the member can read the
                // history but can't reply until the admin starts a new conversation.
                row.getStyle().set("opacity", "0.7");
            }
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
        detailCard.subtitle(whoFor(conv) + " · " + humanizeStatus(conv.status()));
        detailCard.add(new MdThread(toThreadMessages(conv)));

        if ("COMPLAINT".equals(conv.type())) {
            // Complaints are one-shot — the member never replies; show the status instead of a reply bar.
            String note = isTerminal(conv.status())
                ? "This complaint has been reviewed and resolved — no further replies are accepted."
                : "Your complaint is with the TicketHub admin team. Their response will appear here.";
            detailCard.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17), note));
        } else if (!isTerminal(conv.status())) {
            // Inquiries and admin outreach are two-way chats (until closed).
            MdReplyBar reply = new MdReplyBar();
            reply.onSend(this::handleReply);
            detailCard.add(reply);
        } else {
            // Closed inquiry / admin outreach — read-only history, no reply bar.
            detailCard.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
                "This conversation has been closed — you can read the history, but replies are no longer accepted."));
        }
    }

    private void handleReply(String text) {
        switch (presenter.reply(AuthSession.token(), selectedId, text)) {
            case SupportInboxPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Reply sent.");
                reload(); // refresh thread, ordering, and unread badges; keeps selection
            }
            case SupportInboxPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case SupportInboxPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not send your reply — please try again.");
        }
    }

    private void showBanner(String message) {
        this.conversations = List.of();
        content.removeAll();
        content.add(banner(message));
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    // -- Lookups --------------------------------------------------------------

    private int indexOf(String conversationId) {
        if (conversationId == null) return -1;
        for (int i = 0; i < conversations.size(); i++) {
            if (conversations.get(i).conversationId().equals(conversationId)) return i;
        }
        return -1;
    }

    private ConversationDTO byId(String conversationId) {
        int idx = indexOf(conversationId);
        return idx < 0 ? null : conversations.get(idx);
    }

    /** A closed admin → member outreach thread: read-only for the member (readable, but no reply). */
    private static boolean isLocked(ConversationDTO c) {
        return "DIRECT".equals(c.type()) && isTerminal(c.status());
    }

    // -- Display mapping ------------------------------------------------------

    private static List<MdThread.Message> toThreadMessages(ConversationDTO conv) {
        String other = whoFor(conv);
        List<MdThread.Message> out = new ArrayList<>();
        for (MessageDTO m : conv.messages()) {
            boolean me = "MEMBER".equals(m.senderType());
            out.add(new MdThread.Message(me ? "You" : other, relativeTime(m.sentAt()), me, m.body()));
        }
        return out;
    }

    /**
     * Label for the member's counterparty — the resolved display name of whichever party isn't the
     * member. On inquiries/complaints the member initiated, that's the counterparty (company /
     * "TicketHub Support"); on admin outreach the member is the counterparty, so it's the initiator.
     */
    private static String whoFor(ConversationDTO c) {
        return "MEMBER".equals(c.initiatorType())
            ? c.counterpartyDisplayName()
            : c.initiatorDisplayName();
    }

    private static String iconFor(String type) {
        return switch (type) {
            case "COMPLAINT" -> "warning";
            case "INQUIRY" -> "comment";
            default -> "comment";
        };
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
