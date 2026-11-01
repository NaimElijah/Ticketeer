package com.ticketing.system.ui.presenters.messaging;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code CompanyInquiryInboxView} (#268). Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call decision tree is unit-testable
 * in isolation (the view reads the token from {@code AuthSession} and passes it in, mirroring
 * {@code SupportInboxPresenter} / {@code OwnerDashboardPresenter}).
 *
 * <p>Resolves which of the signed-in member's companies to view ({@code companyId} param, else the
 * first — same idiom as {@code OwnerDashboardPresenter}) and lists that company's member
 * <em>inquiries</em>, excluding admin → company announcements (the company is the counterparty on
 * both, so we filter by {@code INQUIRY} type). Replies append to a thread; "close" terminates it —
 * the company side is resolved from the token by the service in both cases.
 */
@Component
public class CompanyInquiryInboxPresenter {

    private static final String INQUIRY = "INQUIRY";
    private static final String COMPANY = "COMPANY";

    private final MessagingService messagingService;
    private final CompanyManagementService companyManagementService;

    @Autowired
    public CompanyInquiryInboxPresenter(MessagingService messagingService,
                                        CompanyManagementService companyManagementService) {
        this.messagingService = messagingService;
        this.companyManagementService = companyManagementService;
    }

    /**
     * Loads the member's companies plus the selected company's member inquiries. When
     * {@code companyId} is null (first load) or unknown, the first company is selected.
     */
    public Outcome loadFor(String token, Integer companyId) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            List<MyCompanyDTO> companies = companyManagementService.findMyCompanies(token);
            if (companies.isEmpty()) {
                return new Outcome.NoCompany();
            }
            MyCompanyDTO selected = companies.stream()
                .filter(c -> companyId != null && c.companyId() == companyId)
                .findFirst()
                .orElse(companies.get(0));
            List<ConversationDTO> inquiries = messagingService
                .viewCompanyInbox(token, selected.companyId()).stream()
                .filter(c -> INQUIRY.equals(c.type()))
                .toList();
            return new Outcome.Success(companies, selected, inquiries);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Opens a single inquiry thread for the dedicated respond page, marking it read for the caller
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

    /** Appends a reply to {@code conversationId} on behalf of the signed-in company. */
    public ActionOutcome reply(String token, String conversationId, String body) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            // senderId/senderType are ignored by the service (it resolves the caller's side from
            // the token), so the sentinel values below are never read.
            messagingService.sendMessage(token,
                new SendMessageRequestDTO(conversationId, 0, COMPANY, body));
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Closes {@code conversationId} (no further messages allowed). The issue's "mark resolved"
     * maps here: no participant-callable resolve exists, so we reuse the close transition — the
     * thread ends as CLOSED. (RESOLVED stays complaint/admin-only.)
     */
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
     * for the signed-in company's side and, on success, records it in {@code opened}, so {@link #badge}
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

    /** Sealed outcome the view switches on to render the inbox or an empty/error state. */
    public sealed interface Outcome {
        record Success(List<MyCompanyDTO> companies, MyCompanyDTO selected,
                       List<ConversationDTO> conversations) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record NoCompany() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Sealed outcome for loading a single inquiry thread (respond page). */
    public sealed interface SingleOutcome {
        record Success(ConversationDTO conversation) implements SingleOutcome { }
        record NotAuthenticated() implements SingleOutcome { }
        record Failure(String reason) implements SingleOutcome { }
    }

    /** Result of a reply / close / open the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
