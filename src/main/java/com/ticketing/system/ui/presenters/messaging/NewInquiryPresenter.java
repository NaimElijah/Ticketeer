package com.ticketing.system.ui.presenters.messaging;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CompanySummaryDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code NewInquiryView} (II.3.10 — a member opens an inquiry with a company).
 * Vaadin-free: searches production companies for the picker and opens the member→company INQUIRY
 * conversation. The member's identity comes from the token; the service resolves it.
 */
@Component
public class NewInquiryPresenter {

    private final MessagingService messagingService;
    private final CatalogService catalogService;

    /**
     * Creates the presenter.
     *
     * @param messagingService opens the inquiry
     * @param catalogService   searches companies for the picker
     */
    @Autowired
    public NewInquiryPresenter(MessagingService messagingService, CatalogService catalogService) {
        this.messagingService = messagingService;
        this.catalogService = catalogService;
    }

    /**
     * Searches active companies by name for the picker. Company names are public — no token needed.
     *
     * @param query part of a company name; {@code null} or blank lists every active company
     */
    public SearchOutcome searchCompanies(String query) {
        try {
            return new SearchOutcome.Success(catalogService.searchCompaniesByName(query));
        } catch (RuntimeException e) {
            return new SearchOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Opens an INQUIRY to {@code companyId} on behalf of the signed-in member.
     *
     * @param token     the member's token; {@code null} gives {@link ActionOutcome.NotAuthenticated}
     * @param companyId the company to write to
     * @param subject   the inquiry's subject
     * @param body      the first message
     */
    public ActionOutcome submit(String token, int companyId, String subject, String body) {
        if (token == null) {
            return new ActionOutcome.NotAuthenticated();
        }
        try {
            ConversationDTO conversation = messagingService.startConversation(token,
                new StartConversationRequestDTO(companyId, subject, body));
            return new ActionOutcome.Success(conversation.conversationId());
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome for the company picker search. */
    public sealed interface SearchOutcome {
        /**
         * The search ran.
         *
         * @param companies the matching active companies, sorted by name, possibly none
         */
        record Success(List<CompanySummaryDTO> companies) implements SearchOutcome { }

        /**
         * The search failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements SearchOutcome { }
    }

    /** Result of submitting an inquiry the view reacts to (carries the new conversation id). */
    public sealed interface ActionOutcome {
        /**
         * The inquiry is opened.
         *
         * @param conversationId the new conversation's id
         */
        record Success(String conversationId) implements ActionOutcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements ActionOutcome { }

        /**
         * The service refused the inquiry or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements ActionOutcome { }
    }
}
