package com.ticketing.system.reporting.application.service;
// AuthenticationService lives in identity; reporting reads it (a downward, allowed dependency).
import com.ticketing.system.identity.application.service.AuthenticationService;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dtoMappers.OrderReceiptMapper;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

// Read-side service for member-facing personal account queries.
// Owns UC-16 (View Personal Purchase History).
// Reserved for additional member-side reads (UC-15 if it returns from Cancelled, profile views, etc.).
// Separated from AuthenticationService (which is auth-flow only) so personal-data reads don't
// stretch the auth boundary — see design_walkthrough_summary.md §6.
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
public class MemberAccountService {

    private final AuthenticationService authenticationService; // For user identity verification, if needed for future
                                                               // methods.
    private final OrderReceiptRepository orderReceiptRepository;
    private final TicketRepository ticketRepository;
    private final CatalogEventDisplayPort eventDisplayPort; // For event/zone name lookups in history records.

    public MemberAccountService(
            AuthenticationService authenticationService,
            OrderReceiptRepository orderReceiptRepository,
            TicketRepository ticketRepository,
            CatalogEventDisplayPort eventDisplayPort) {
        this.authenticationService = authenticationService;
        this.orderReceiptRepository = orderReceiptRepository;
        this.ticketRepository = ticketRepository;
        this.eventDisplayPort = eventDisplayPort;
    }

    /**
     * UC-16: the signed-in member's purchase history, each order with its tickets' current status.
     *
     * <p>The member is the one the token names, exactly as in {@link #viewMyReceipt}. The
     * {@code userId} the DTO carries is ignored: a caller can set it to anyone.
     *
     * @param authToken the member's credential; only its {@code token} is read
     * @return the member's orders, empty only when the member has bought nothing
     * @throws InvalidTokenException if the token is missing or blank, or the session manager rejects it
     * @throws com.ticketing.system.shared.exception.SessionExpiredException if the session has expired
     * @throws RuntimeException a failure of the receipt, ticket or catalog store, propagated unchanged
     *         so it is never shown as an empty history (#192)
     */
    public PurchaseHistoryDTO viewMyHistory(AuthTokenDTO authToken) {
        String token = authToken == null ? null : authToken.token();
        if (!authenticationService.validateToken(token)) {
            throw new InvalidTokenException();
        }
        int userId = authenticationService.extractUserId(token);

        OrderReceiptMapper receiptMapper = new OrderReceiptMapper();
        // The buyer is the member themselves and the account view shows no company, so neither
        // repository is passed (the mapper leaves those names null).
        List<PurchaseRecordDTO> purchaseRecords = orderReceiptRepository.findByHolderUserId(userId).stream()
                .map(receipt -> receiptMapper.toPurchaseRecordDTO(
                        receipt, ticketRepository, eventDisplayPort, null, null))
                .toList();

        log.debug("Retrieved purchase history for userId={}, recordsCount={}", userId, purchaseRecords.size());
        return new PurchaseHistoryDTO(purchaseRecords);
    }

    // UC-16 (single receipt): one member-owned order, enriched for the receipt page (#276). It
    // throws, as viewMyHistory does, so the presenter can tell apart an auth failure, a missing
    // receipt, and a receipt that isn't the caller's (403).
    public PurchaseRecordDTO viewMyReceipt(String token, int receiptId) {
        log.info("Received request to view receipt {} for the authenticated member", receiptId);
        if (!authenticationService.validateToken(token)) {
            throw new InvalidTokenException();
        }
        int userId = authenticationService.extractUserId(token);

        OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(receiptId)
                .orElseThrow(() -> new EntityNotFoundException("OrderReceipt", receiptId));

        // Authorization is a domain concern: a member may only view their own receipt.
        Integer holderUserId = receipt.getHolderUserId();
        if (holderUserId == null || holderUserId != userId) {
            throw new UnauthorizedActionException("view receipt " + receiptId, userId);
        }

        // Buyer is the member themselves and company isn't shown on the receipt, so
        // pass null for
        // those repos (the mapper yields null for the unresolved names — see
        // OrderReceiptMapper).
        return new OrderReceiptMapper().toPurchaseRecordDTO(
                receipt, ticketRepository, eventDisplayPort, null, null);
    }

}
