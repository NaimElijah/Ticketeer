package com.ticketing.system.ui.views.company;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.messaging.MdReplyBar;
import com.ticketing.system.ui.components.messaging.MdThread;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
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
 * Dedicated page where an eligible company role-holder (owner / manager with RESPOND_INQUIRIES)
 * answers a member inquiry: the full chat thread plus a reply box. Replies surface in the member's
 * Support inbox and the member can reply again (two-way chat). The thread can also be closed here.
 * Backed by {@link CompanyInquiryInboxPresenter} → {@code MessagingService}.
 */
@Route(value = "owner/inquiries/:id/respond", layout = WorkspaceLayout.class)
@PageTitle("Respond to Inquiry · TicketHub")
@PermitAll
@RequireCapability(Capability.RESPOND_INQUIRIES)
public class CompanyInquiryRespondView extends LkPage implements BeforeEnterObserver {

    private final CompanyInquiryInboxPresenter presenter;
    private final Div content = new Div();

    private String conversationId;

    public CompanyInquiryRespondView(CompanyInquiryInboxPresenter presenter) {
        this.presenter = presenter;
        title("Respond to Inquiry");
        add(content);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        this.conversationId = event.getRouteParameters().get("id").orElse(null);
        reload();
    }

    private void reload() {
        content.removeAll();
        if (conversationId == null) {
            content.add(banner("No inquiry selected."));
            return;
        }
        switch (presenter.loadOne(AuthSession.token(), conversationId)) {
            case CompanyInquiryInboxPresenter.SingleOutcome.Success ok -> render(ok.conversation());
            case CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated ignored ->
                content.add(banner("Your session has expired — please sign in again."));
            case CompanyInquiryInboxPresenter.SingleOutcome.Failure fail ->
                content.add(banner(Lk.withReason("Could not load the inquiry", fail.reason())));
        }
    }

    private void render(ConversationDTO conv) {
        subtitle("From " + conv.initiatorDisplayName() + " · " + humanizeStatus(conv.status()));

        LkCard card = new LkCard(conv.subject()).pad(20);
        card.add(new MdThread(toThreadMessages(conv)));

        if (isTerminal(conv.status())) {
            card.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
                "This inquiry is closed — no further replies are accepted."));
        } else {
            MdReplyBar reply = new MdReplyBar();
            reply.onSend(this::handleReply);
            card.add(reply);

            LkRow actions = new LkRow().gap(8).justify("flex-end");
            actions.getStyle().set("margin-top", "12px");
            actions.add(
                new LkBtn("Back to Inquiries").variant(LkBtn.Variant.tertiary)
                    .onClick(e -> UI.getCurrent().navigate(CompanyInquiryInboxView.class)),
                new LkBtn("Close Inquiry").variant(LkBtn.Variant.secondary)
                    .onClick(e -> handleClose()));
            card.add(actions);
        }
        content.add(card);
    }

    private void handleReply(String text) {
        switch (presenter.reply(AuthSession.token(), conversationId, text)) {
            case CompanyInquiryInboxPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Reply sent.");
                reload(); // refresh thread + status
            }
            case CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case CompanyInquiryInboxPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not send your reply — please try again.");
        }
    }

    private void handleClose() {
        switch (presenter.close(AuthSession.token(), conversationId)) {
            case CompanyInquiryInboxPresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Inquiry closed.");
                UI.getCurrent().navigate(CompanyInquiryInboxView.class);
            }
            case CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case CompanyInquiryInboxPresenter.ActionOutcome.Failure fail ->
                Toasts.failure("Could not close the inquiry — please try again.");
        }
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    // -- Display mapping ------------------------------------------------------

    /** Company vantage: COMPANY messages are "You"; the member's messages show their username. */
    private static List<MdThread.Message> toThreadMessages(ConversationDTO conv) {
        List<MdThread.Message> out = new ArrayList<>();
        for (MessageDTO m : conv.messages()) {
            boolean me = "COMPANY".equals(m.senderType());
            String from = me ? "You" : conv.initiatorDisplayName();
            out.add(new MdThread.Message(from, relativeTime(m.sentAt()), me, m.body()));
        }
        return out;
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
