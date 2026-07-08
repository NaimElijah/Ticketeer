package com.ticketing.system.Presentation.presenters.messaging;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.Core.Application.dto.SendMessageRequestDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.Core.Application.services.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;

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
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Loads a single inquiry thread for the dedicated respond page. */
    public SingleOutcome loadOne(String token, String conversationId) {
        if (token == null) {
            return new SingleOutcome.NotAuthenticated();
        }
        try {
            return new SingleOutcome.Success(messagingService.viewConversation(token, conversationId));
        } catch (InvalidTokenException e) {
            return new SingleOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SingleOutcome.Failure(e.getMessage());
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
            return new ActionOutcome.Failure(e.getMessage());
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
            return new ActionOutcome.Failure(e.getMessage());
        }
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

    /** Result of a reply / close the view reacts to. */
    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
