package com.ticketing.system.ui.presenters.messaging;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.dto.OutreachResultDTO;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code AdminSendMessagesView} (II.6.3.2 — admin proactive outreach). Holds no
 * Vaadin imports so the outcome → UI translation lives in the view and the decision tree is
 * unit-testable (the view reads the token from {@code AuthSession} and passes it in).
 *
 * <p>{@code send} opens one two-way DIRECT chat per recipient (so each lands in that member's
 * Support Inbox); {@code load} reads the sent history and groups the per-recipient fan-out back
 * into logical sends (bucketed by {@code initiatorId + subject + body}); {@code searchUsers} backs
 * the recipient search box.
 */
@Component
public class AdminSendMessagesPresenter {

    private final MessagingService messagingService;
    private final MemberQueryService memberQueryService;

    /**
     * Creates the presenter.
     *
     * @param messagingService   sends the outreach and reads the sent history
     * @param memberQueryService searches members for the recipient picker
     */
    @Autowired
    public AdminSendMessagesPresenter(MessagingService messagingService,
                                      MemberQueryService memberQueryService) {
        this.messagingService = messagingService;
        this.memberQueryService = memberQueryService;
    }

    /** Loads the sent-outreach history, grouped into logical sends (newest first). */
    public Outcome load(String token) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            return new Outcome.Success(group(messagingService.viewSentOutreach(token)));
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Sends outreach to the resolved recipients; returns the recipient count on success.
     *
     * @param token              the admin's token; {@code null} gives {@link ActionOutcome.NotAuthenticated}
     * @param subject            the message subject
     * @param body               the message text
     * @param recipientMemberIds the members to write to; used only when neither {@code allMembers} nor
     *     {@code allProducers} is set
     * @param allMembers         {@code true} to write to every member
     * @param allProducers       {@code true} to write to the owners of every active company
     */
    public ActionOutcome send(String token, String subject, String body,
                              List<Integer> recipientMemberIds, boolean allMembers, boolean allProducers) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            OutreachResultDTO result = messagingService.sendOutreach(token,
                new OutreachRequestDTO(subject, body, recipientMemberIds, allMembers, allProducers));
            return new ActionOutcome.Success(result.recipientCount());
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Searches members by username substring for the recipient picker. */
    public SearchOutcome searchUsers(String token, String query) {
        if (token == null) {
            return new SearchOutcome.NotAuthenticated();
        }
        try {
            return new SearchOutcome.Success(memberQueryService.searchByUsername(token, query));
        } catch (InvalidTokenException e) {
            return new SearchOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SearchOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    // -- Grouping the per-recipient fan-out into logical sends -----------------

    private static List<SentOutreach> group(List<ConversationDTO> outreach) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (ConversationDTO c : outreach) {
            String body = c.messages().isEmpty() ? "" : c.messages().get(0).body();
            String key = c.initiatorId() + "\u0000" + c.subject() + "\u0000" + body;
            buckets.computeIfAbsent(key, k -> new Bucket(c.subject())).add(c);
        }
        return buckets.values().stream()
            .map(Bucket::toSentOutreach)
            .sorted(Comparator.comparing(SentOutreach::sentAt,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    /** Mutable accumulator for one logical send (a group of fan-out conversations). */
    private static final class Bucket {
        private final String subject;
        private int count;
        private LocalDateTime earliest;

        Bucket(String subject) { this.subject = subject; }

        void add(ConversationDTO c) {
            count++;
            if (c.createdAt() != null && (earliest == null || c.createdAt().isBefore(earliest))) {
                earliest = c.createdAt();
            }
        }

        SentOutreach toSentOutreach() {
            return new SentOutreach(earliest, subject, count);
        }
    }

    /**
     * One logical send row for the sent-history grid: the conversations that share an initiator,
     * subject and first message.
     *
     * @param sentAt         the earliest creation time among those conversations, or {@code null} when
     *     none carries one
     * @param subject        the shared subject
     * @param recipientCount how many conversations the send opened
     */
    public record SentOutreach(LocalDateTime sentAt, String subject, int recipientCount) { }

    /** Sealed outcome the view switches on to render the history grid or an empty/error state. */
    public sealed interface Outcome {
        /**
         * The history loaded.
         *
         * @param history the logical sends, possibly none
         */
        record Success(List<SentOutreach> history) implements Outcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome { }

        /**
         * The service refused the read or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }

    /** Result of a send the view reacts to (carries the recipient count for the confirmation). */
    public sealed interface ActionOutcome {
        /**
         * The service sent the outreach.
         *
         * @param recipientCount how many recipients the send reached
         */
        record Success(int recipientCount) implements ActionOutcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements ActionOutcome { }

        /**
         * The service refused the send, for example for a send that matches no recipient, or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements ActionOutcome { }
    }

    /** Sealed outcome for the recipient search box. */
    public sealed interface SearchOutcome {
        /**
         * The search ran.
         *
         * @param results the members whose username contains the query, possibly none
         */
        record Success(List<MemberSearchResultDTO> results) implements SearchOutcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements SearchOutcome { }

        /**
         * The service refused the search or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements SearchOutcome { }
    }
}
