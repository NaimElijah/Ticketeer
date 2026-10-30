package com.ticketing.system.ui.views.admin;

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
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.components.messaging.MdThread;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.DisplayTime;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated page where an admin reads a complaint (subject + full description + any prior message)
 * and writes the single response. Sending it delivers the reply to the member's Support Inbox and
 * marks the complaint RESOLVED (one-shot — the member cannot reply). Backed by
 * {@link AdminComplaintQueuePresenter} → {@code MessagingService}.
 */
@Route(value = "admin/complaints/:id/respond", layout = PlatformAdminLayout.class)
@PageTitle("Respond to Complaint · Admin")
@PermitAll
@RequireCapability(Capability.MANAGE_COMPLAINTS)
public class AdminComplaintRespondView extends LkPage implements BeforeEnterObserver {

    private final AdminComplaintQueuePresenter presenter;
    private final DisplayTime displayTime;
    private final Div content = new Div();
    private final TextArea reply = new TextArea("Your response");

    private String conversationId;

    public AdminComplaintRespondView(AdminComplaintQueuePresenter presenter, DisplayTime displayTime) {
        this.presenter = presenter;
        this.displayTime = displayTime;
        LkTextLimit.apply(reply);
        title("Respond to Complaint");
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
            content.add(banner("No complaint selected."));
            return;
        }
        switch (presenter.loadOne(AuthSession.token(), conversationId)) {
            case AdminComplaintQueuePresenter.SingleOutcome.Success ok -> render(ok.conversation());
            case AdminComplaintQueuePresenter.SingleOutcome.NotAuthenticated ignored ->
                content.add(banner("Your session has expired — please sign in again."));
            case AdminComplaintQueuePresenter.SingleOutcome.Failure fail ->
                content.add(banner(Lk.withReason("Could not load the complaint", fail.reason())));
        }
    }

    private void render(ConversationDTO conv) {
        subtitle("From " + conv.initiatorDisplayName() + " · " + humanizeStatus(conv.status()));

        LkCard card = new LkCard(conv.subject()).pad(20);
        card.add(new MdThread(toThreadMessages(conv)));

        if (isTerminal(conv.status())) {
            card.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
                "This complaint has been resolved — no further responses are accepted."));
        } else {
            reply.setPlaceholder("Write your response to the member…");
            reply.setMinHeight("140px");
            reply.setWidthFull();
            card.add(reply);

            LkRow actions = new LkRow().gap(8).justify("flex-end");
            actions.getStyle().set("margin-top", "16px");
            actions.add(
                new LkBtn("Cancel").variant(LkBtn.Variant.tertiary)
                    .onClick(e -> UI.getCurrent().navigate(AdminComplaintQueueView.class)),
                new LkBtn("Send & Resolve").variant(LkBtn.Variant.primary)
                    .icon(new LkIcon("arrowRight", 15))
                    .onClick(e -> submit()));
            card.add(actions);
        }
        content.add(card);
    }

    private void submit() {
        if (reply.isEmpty()) {
            Toasts.failure("Please write a response.");
            return;
        }
        switch (presenter.respond(AuthSession.token(), conversationId, reply.getValue())) {
            case AdminComplaintQueuePresenter.ActionOutcome.Success ignored -> {
                Toasts.success("Response sent — complaint resolved.");
                UI.getCurrent().navigate(AdminComplaintQueueView.class);
            }
            case AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case AdminComplaintQueuePresenter.ActionOutcome.Failure fail ->
                Toasts.failure(Lk.withReason("Could not send your response", fail.reason()));
        }
    }

    private Component banner(String message) {
        return new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message);
    }

    // -- Display mapping ------------------------------------------------------

    /** Admin vantage: ADMIN messages are "You"; the member's message shows their username. */
    private List<MdThread.Message> toThreadMessages(ConversationDTO conv) {
        List<MdThread.Message> out = new ArrayList<>();
        for (MessageDTO m : conv.messages()) {
            boolean me = "ADMIN".equals(m.senderType());
            String from = me ? "You" : conv.initiatorDisplayName();
            out.add(new MdThread.Message(from, displayTime.ageShort(m.sentAt()), me, m.body()));
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
}
