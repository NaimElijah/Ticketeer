package com.ticketing.system.Presentation.presenters.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * MVP presenter for {@code SubmitComplaintView} (#267). Holds no Vaadin imports so
 * the outcome → UI translation lives in the view and the service-call decision tree
 * is unit-testable in isolation (the view passes {@code AuthSession.token()} in,
 * mirroring {@code MyInvitationsPresenter}).
 *
 * <p>A complaint always routes to the admin group, so there is no audience choice —
 * the form's "About" category is carried through as the {@code relatedEntityRef}
 * context, which the service folds into the message body.
 */
@Component
public class SubmitComplaintPresenter {

    private final MessagingService messagingService;

    @Autowired
    public SubmitComplaintPresenter(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * Files a complaint on behalf of the signed-in member and returns the new
     * conversation id so the view can redirect the inbox to it.
     */
    public Outcome submit(String token, String subject, String body, String relatedEntityRef) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            // memberId is ignored by the service (it derives the real id from the
            // authenticated token), so 0 is a safe sentinel here.
            ConversationDTO conversation = messagingService.submitComplaint(
                token, new SubmitComplaintRequestDTO(0, subject, body, relatedEntityRef));
            return new Outcome.Success(conversation.conversationId());
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on to redirect, re-auth, or surface an error. */
    public sealed interface Outcome {
        record Success(String conversationId) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
