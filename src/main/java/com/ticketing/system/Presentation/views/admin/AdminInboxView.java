package com.ticketing.system.Presentation.views.admin;
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
import com.ticketing.system.Presentation.layouts.PlatformAdminLayout;
import com.ticketing.system.Presentation.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.Presentation.security.Capability;
import com.ticketing.system.Presentation.security.RequireCapability;
import com.ticketing.system.Presentation.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin outreach inbox (II.6.3.2): the admin's two-way DIRECT chats with members they've messaged.
 * Master/detail like {@code SupportInboxView}; each non-terminal thread has a reply bar plus a
 * "Close conversation" action (after which the member can no longer open it). Backed by
 * {@link AdminInboxPresenter} → {@code MessagingService}.
 */
@Route(value = "admin/inbox", layout = PlatformAdminLayout.class)
@PageTitle("Admin Inbox · Admin")
@PermitAll
@RequireCapability(Capability.BROADCAST_ANNOUNCEMENT)
public class AdminInboxView extends LkPage {

    private final AdminInboxPresenter presenter;

    /** Holds the master/detail split, or an empty/error banner, so {@link #reload()} can rebuild it. */
    private final Div content = new Div();

    private List<ConversationDTO> conversations = List.of();
    private final List<MdConvRow> convRows = new ArrayList<>();
    private String selectedId;
    private LkCard detailCard;

    public AdminInboxView(AdminInboxPresenter presenter) {
        this.presenter = presenter;

        title("Admin Inbox");
        subtitle("Your conversations with members you've messaged.");
        add(content);
        reload();
    }

    // -- Loading / rendering --------------------------------------------------

    private void reload() {
        switch (presenter.load(AuthSession.token())) {
            case AdminInboxPresenter.Outcome.Success ok -> renderInbox(ok.conversations());
            case AdminInboxPresenter.Outcome.NotAuthenticated ignored -> showBanner(
                "Your session has expired — please sign in again.");
            case AdminInboxPresenter.Outcome.Failure fail -> showBanner(
                Lk.withReason("Could not load your conversations", fail.reason()));
        }
    }

    private void renderInbox(List<ConversationDTO> list) {
        this.conversations = list;
        content.removeAll();
        if (list.isEmpty()) {
            content.add(banner("No conversations yet — start one from Send Messages."));
            return;
        }

        int selIdx = indexOf(selectedId);
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
            MdConvRow row = new MdConvRow("comment", c.subject(), c.counterpartyDisplayName(),
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
        detailCard.subtitle(conv.counterpartyDisplayName() + " · " + humanizeStatus(conv.status()));
        detailCard.add(new MdThread(toThreadMessages(conv)));

        if (isTerminal(conv.status())) {
            detailCard.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
                "This conversation is closed — no further messages."));
        } else {
            Div actionRow = new Div();
            actionRow.addClassName("md-detail-actions");
            actionRow.add(new LkBtn("Close Conversation").variant(LkBtn.Variant.tertiary)
                .onClick(e -> handleClose()));
            detailCard.add(actionRow);

            MdReplyBar reply = new MdReplyBar();
            reply.onSend(this::handleReply);
            detailCard.add(reply);
        }
    }

    private void handleReply(String text) {
        switch (presenter.reply(AuthSession.token(), selectedId, text)) {
            case AdminInboxPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Reply sent.");
                reload();
            }
            case AdminInboxPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminInboxPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not send your reply — please try again.");
        }
    }

    private void handleClose() {
        switch (presenter.close(AuthSession.token(), selectedId)) {
            case AdminInboxPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Conversation closed.");
                reload();
            }
            case AdminInboxPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminInboxPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not close the conversation — please try again.");
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

    // -- Display mapping ------------------------------------------------------

    /** Admin vantage: ADMIN messages are "You"; the member's messages show their username. */
    private static List<MdThread.Message> toThreadMessages(ConversationDTO conv) {
        List<MdThread.Message> out = new ArrayList<>();
        for (MessageDTO m : conv.messages()) {
            boolean me = "ADMIN".equals(m.senderType());
            String from = me ? "You" : conv.counterpartyDisplayName();
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

    private static String humanizeStatus(String status) {
        if (status == null || status.isEmpty()) return "";
        return status.charAt(0) + status.substring(1).toLowerCase();
    }

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
