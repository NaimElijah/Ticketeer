package com.ticketing.system.Presentation.presenters.messaging;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.CompanySummaryDTO;
import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.StartConversationRequestDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.Core.Application.services.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * MVP presenter for {@code NewInquiryView} (II.3.10 — a member opens an inquiry with a company).
 * Vaadin-free: searches production companies for the picker and opens the member→company INQUIRY
 * conversation. The member's identity comes from the token; the service resolves it.
 */
@Component
public class NewInquiryPresenter {

    private final MessagingService messagingService;
    private final CatalogService catalogService;

    @Autowired
    public NewInquiryPresenter(MessagingService messagingService, CatalogService catalogService) {
        this.messagingService = messagingService;
        this.catalogService = catalogService;
    }

    /** Searches active companies by name for the picker. Company names are public — no token needed. */
    public SearchOutcome searchCompanies(String query) {
        try {
            return new SearchOutcome.Success(catalogService.searchCompaniesByName(query));
        } catch (RuntimeException e) {
            return new SearchOutcome.Failure(e.getMessage());
        }
    }

    /** Opens an INQUIRY to {@code companyId} on behalf of the signed-in member. */
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
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome for the company picker search. */
    public sealed interface SearchOutcome {
        record Success(List<CompanySummaryDTO> companies) implements SearchOutcome { }
        record Failure(String reason) implements SearchOutcome { }
    }

    /** Result of submitting an inquiry the view reacts to (carries the new conversation id). */
    public sealed interface ActionOutcome {
        record Success(String conversationId) implements ActionOutcome { }
        record NotAuthenticated() implements ActionOutcome { }
        record Failure(String reason) implements ActionOutcome { }
    }
}
