package com.ticketing.system.ui.presenters.messaging;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * MVP presenter for {@code AdminInboxView} (II.6.3.2 — admin outreach inbox). Holds no Vaadin
 * imports so the outcome → UI translation lives in the view and the decision tree is unit-testable.
 *
 * <p>Lists the admin's own DIRECT outreach conversations, lets them reply (two-way chat), and close
 * a conversation (terminal — the member then can't reopen it). The admin's side is resolved from the
 * token by the service. Mirrors {@code SupportInboxPresenter} with an added {@code close}.
 */
@Component
public class AdminInboxPresenter {

    private static final String ADMIN = "ADMIN";

    private final MessagingService messagingService;

    @Autowired
    public AdminInboxPresenter(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /** Loads the admin's outreach conversations, newest first. */
    public Outcome load(String token) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            return new Outcome.Success(messagingService.viewAdminInbox(token));
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Appends the admin's reply to {@code conversationId}. */
    public ActionOutcome reply(String token, String conversationId, String body) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            // senderId/senderType are ignored by the service (it resolves the caller's side from the
            // token), so the sentinel values below are never read.
            messagingService.sendMessage(token,
                new SendMessageRequestDTO(conversationId, 0, ADMIN, body));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    /** Closes {@code conversationId} (terminal — neither side can post afterwards). */
    public ActionOutcome close(String token, String conversationId) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            messagingService.closeConversation(token, conversationId);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on to render the inbox or an empty/error state. */
    public sealed interface Outcome {
        record Success(List<ConversationDTO> conversations) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Result of a reply / close the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
