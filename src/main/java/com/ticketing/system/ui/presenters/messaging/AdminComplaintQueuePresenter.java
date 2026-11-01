package com.ticketing.system.ui.presenters.messaging;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.ComplaintFilterDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.RespondToComplaintRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code AdminComplaintQueueView} (#269). Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call decision tree is unit-testable
 * in isolation (the view reads the token from {@code AuthSession} and passes it in, mirroring
 * {@code SupportInboxPresenter} / {@code CompanyInquiryInboxPresenter}).
 *
 * <p>Lists the platform-wide complaint queue ({@code viewAllComplaints}, admin-gated, status
 * filtered server-side). Complaints are one-shot: {@code respond} sends the admin's single reply
 * via {@code respondToComplaint}, which appends the message and resolves the thread (the member
 * can never reply). {@code loadOne} fetches a single complaint for the dedicated respond page.
 */
@Component
public class AdminComplaintQueuePresenter {

    private static final String ALL = "All";
    private static final String GROUP_OPEN = "Open";
    private static final String GROUP_RESOLVED = "Resolved";
    private static final String RESOLVED = "RESOLVED";

    private final MessagingService messagingService;

    @Autowired
    public AdminComplaintQueuePresenter(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    /**
     * Loads the complaint queue grouped by the UI status filter: "All" → everything, "Open" →
     * OPEN+RESPONDED (not yet resolved), "Resolved" → RESOLVED+CLOSED (terminal). A single
     * server-side status can't express those groups, so the whole queue is fetched and grouped
     * in-memory ("All"/blank → no grouping).
     */
    public Outcome load(String token, String group) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            List<ConversationDTO> all = messagingService.viewAllComplaints(
                token, new ComplaintFilterDTO(null, null, null, null));
            List<ConversationDTO> filtered = all.stream()
                .filter(c -> inGroup(c.status(), group))
                .toList();
            return new Outcome.Success(filtered);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** UI status group → which raw ConversationStatus values it includes. "All"/blank → everything. */
    private static boolean inGroup(String status, String group) {
        if (group == null || group.isBlank() || ALL.equals(group)) {
            return true;
        }
        return switch (group) {
            case GROUP_OPEN     -> "OPEN".equals(status) || "RESPONDED".equals(status);
            case GROUP_RESOLVED -> "RESOLVED".equals(status) || "CLOSED".equals(status);
            default             -> true;
        };
    }

    /**
     * Opens a single complaint thread for the dedicated respond page, marking it read for the caller
     * first, so the thread it returns already counts nothing unread. A failed mark still shows the
     * thread; only an invalid or expired session stops the page.
     */
    public SingleOutcome loadOne(String token, String conversationId) {
        if (token == null) {
            return new SingleOutcome.NotAuthenticated();
        }
        // Any other failed mark still shows the thread: marking read is a side effect of opening it, and the
        // reader can do nothing about its failure. markRead passes the failure to ServiceErrors, which logs
        // an internal one at ERROR. A failure that also stops the thread loading surfaces from
        // viewConversation below.
        if (markRead(token, conversationId) instanceof ActionOutcome.NotAuthenticated) {
            return new SingleOutcome.NotAuthenticated();
        }
        try {
            return new SingleOutcome.Success(messagingService.viewConversation(token, conversationId));
        } catch (InvalidTokenException e) {
            return new SingleOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SingleOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Sends the admin's single response to a complaint, which resolves it (one-shot — the member
     * cannot reply). The admin is resolved from the token by the service.
     */
    public ActionOutcome respond(String token, String conversationId, String body) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            // adminId is ignored by the service (it resolves the caller from the token); RESOLVED is
            // the terminal status the response always lands in.
            messagingService.respondToComplaint(token,
                new RespondToComplaintRequestDTO(conversationId, 0, body, RESOLVED));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    // Marks the thread read for the caller's side; open and loadOne call it.
    private ActionOutcome markRead(String token, String conversationId) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            messagingService.markConversationRead(token, conversationId);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Opens {@code conversation} at the reader's request (a row click or a deep link): marks it read
     * for the signed-in admin's side and, on success, records it in {@code opened}, so {@link #badge}
     * shows no badge for it until a newer message arrives. A failed mark records nothing, so the badge
     * stays. A thread the view selects on its own is shown, not opened: a mark reads it for the
     * reader's whole side, so the view must not call this for it (#187).
     *
     * @param token        the signed-in caller's token; {@code null} when signed out
     * @param conversation the thread the reader opened
     * @param opened       the view's own record of the threads it opened
     * @return the outcome of marking the thread read
     */
    public ActionOutcome open(String token, ConversationDTO conversation, OpenedThreads opened) {
        ActionOutcome outcome = markRead(token, conversation.conversationId());
        if (outcome instanceof ActionOutcome.Success) {
            opened.record(conversation);
        }
        return outcome;
    }

    /**
     * The unread badge for {@code conversation}'s row: none once this view has opened it and no newer
     * message has arrived since, so a re-render from the same list keeps a cleared badge cleared;
     * otherwise the unread count, and none when that is 0.
     *
     * @param conversation the row's thread, as last loaded
     * @param opened       the view's own record of the threads it opened
     * @return the badge text, or empty for no badge
     */
    public Optional<String> badge(ConversationDTO conversation, OpenedThreads opened) {
        return opened.badgeFor(conversation);
    }

    /** Sealed outcome the view switches on to render the queue or an empty/error state. */
    public sealed interface Outcome {
        record Success(List<ConversationDTO> complaints) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Sealed outcome for loading a single complaint thread (respond page). */
    public sealed interface SingleOutcome {
        record Success(ConversationDTO conversation) implements SingleOutcome { }
        record NotAuthenticated() implements SingleOutcome { }
        record Failure(String reason) implements SingleOutcome { }
    }

    /** Result of a respond or an open the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
