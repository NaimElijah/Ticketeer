package com.ticketing.system.Presentation.presenters.messaging;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.SendMessageRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * MVP presenter for {@code SupportInboxView} (#277). Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call decision tree is
 * unit-testable in isolation (the view passes {@code AuthSession.token()} in,
 * mirroring {@code SubmitComplaintPresenter} / {@code MyInvitationsPresenter}).
 *
 * <p>Lists the member's Support Inbox — the inquiries and complaints they opened plus admin →
 * member outreach (where the member is the counterparty). The service's {@code viewMyConversations}
 * already scopes this correctly via {@code findMemberInbox}, so the presenter does no filtering.
 * Replies append to an existing conversation; the member's side is resolved from the token by the
 * service (complaints reject replies — they are one-shot).
 */
@Component
public class SupportInboxPresenter {

    private static final String MEMBER = "MEMBER";

    private final MessagingService messagingService;

    @Autowired
    public SupportInboxPresenter(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /** Loads the signed-in member's Support Inbox conversations, newest first. */
    public Outcome load(String token) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            return new Outcome.Success(messagingService.viewMyConversations(token));
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Appends a reply to {@code conversationId} on behalf of the signed-in member. */
    public ActionOutcome reply(String token, String conversationId, String body) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            // senderId/senderType are ignored by the service (it resolves the caller's
            // side from the token), so the sentinel values below are never read.
            messagingService.sendMessage(token,
                new SendMessageRequestDTO(conversationId, 0, MEMBER, body));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on to render the inbox or an empty state. */
    public sealed interface Outcome {
        record Success(List<ConversationDTO> conversations) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Result of a reply the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
