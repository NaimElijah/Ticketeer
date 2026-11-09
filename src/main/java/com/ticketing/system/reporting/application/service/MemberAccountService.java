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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side for a signed-in member's own account data: their purchase history and single receipts
 * (UC-16).
 *
 * <p>Separate from {@code AuthenticationService}, which handles the authentication flow only, so
 * personal-data reads do not stretch that boundary. Every read authenticates the caller from the
 * session token and returns only that member's data.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class MemberAccountService {

    private final AuthenticationService authenticationService;
    private final OrderReceiptRepository orderReceiptRepository;
    private final TicketRepository ticketRepository;
    private final CatalogEventDisplayPort eventDisplayPort; // For event/zone name lookups in history records.

    /**
     * Creates the service over the identity, receipt, ticket and catalog-display collaborators.
     *
     * @param authenticationService validates a member's token and names the member it belongs to
     * @param eventDisplayPort      resolves event and zone names for the history records
     */
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

    /**
     * UC-16 (single receipt): one of the signed-in member's own orders, enriched for the receipt page
     * (#276). It throws distinct exceptions, as {@link #viewMyHistory} does. {@code ReceiptPresenter}
     * shows a missing receipt and another member's the same way, so receipt ids on other accounts
     * stay hidden.
     *
     * @param token     the member's session token
     * @param receiptId the id of the order receipt to show
     * @return the order record for that receipt
     * @throws InvalidTokenException if the token is missing or blank, or the session manager rejects it
     * @throws com.ticketing.system.shared.exception.SessionExpiredException if the session has expired
     * @throws EntityNotFoundException if no receipt has that id
     * @throws UnauthorizedActionException if the receipt is not held by the member the token names
     */
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

        // Buyer is the member themselves and company isn't shown on the receipt, so pass null for
        // those repos (the mapper yields null for the unresolved names — see OrderReceiptMapper).
        return new OrderReceiptMapper().toPurchaseRecordDTO(
                receipt, ticketRepository, eventDisplayPort, null, null);
    }

}
