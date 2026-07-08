package com.ticketing.system.unit.application;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class MemberAccountServiceTest {

    @Mock AuthenticationService authenticationService;
    @Mock OrderReceiptRepository orderReceiptRepository;
    @Mock TicketRepository ticketRepository;
    @Mock EventRepository eventRepository;
    @Mock OrderReceipt receipt;
    @Mock Ticket ticket;
    @Mock Event event;

    MemberAccountService service;

    static final String VALID_TOKEN   = "valid-token-abc";
    static final String INVALID_TOKEN = "bad-token-xyz";
    static final int    USER_ID       = 42;

    @BeforeEach
    void setUp() {
        service = new MemberAccountService(
                authenticationService,
                orderReceiptRepository,
                ticketRepository,
                eventRepository
        );
    }

    @Test @Disabled("UC-16: viewMyHistory returns own purchase history")
    void givenAuthenticatedMember_whenViewMyHistory_thenOwnHistoryReturned() {
        // Arrange
        int receiptId = 1;
        int eventId = 10;
        LocalDateTime purchaseTime = LocalDateTime.of(2024, 1, 15, 14, 30);
        AuthTokenDTO validAuth = new AuthTokenDTO(VALID_TOKEN, 1000, USER_ID, "member42");
        List<Ticket> tickets = List.of(ticket);
        List<OrderReceipt> receipts = List.of(receipt);

        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByHolderUserId(USER_ID)).thenReturn(receipts);
        Mockito.when(eventRepository.findById(eventId)).thenReturn(event);
        Mockito.when(ticketRepository.findByOrderReceiptId(receiptId)).thenReturn(tickets);
        Mockito.when(receipt.getId()).thenReturn(receiptId);
        // Mockito.when(receipt.geteventId()).thenReturn(eventId);
        Mockito.when(receipt.getPurchaseTime()).thenReturn(purchaseTime);
        Mockito.when(receipt.getTotalAmount()).thenReturn(150.00);
        Mockito.when(event.getName()).thenReturn("Rock Concert");
        // these 4 below lines recently added
        Mockito.when(receipt.getReceiptLines()).thenReturn(List.of(new ReceiptLine(101, 150.00, 2, 1, "15", purchaseTime)));
        Mockito.when(receipt.getTransactionRecords()).thenReturn(List.of());
        Mockito.when(ticket.getId()).thenReturn(101);
        Mockito.when(ticket.getStatus()).thenReturn(TicketStatus.AVAILABLE);


        // Act
        PurchaseHistoryDTO result = service.viewMyHistory(validAuth);

        // Assert — one purchase record with one ticket
        assertThat(result.records()).hasSize(1);

        PurchaseRecordDTO record = result.records().getFirst();
        assertThat(record.orderReceiptId()).isEqualTo(receiptId);
        // assertThat(record.eventId()).isEqualTo(eventId);
        // assertThat(record.eventName()).isEqualTo("Rock Concert");
        assertThat(record.totalPaid()).isEqualByComparingTo(150.00);

        assertThat(record.tickets()).hasSize(1);
        assertThat(record.tickets().getFirst().ticketId()).isEqualTo(101);
        assertThat(record.tickets().getFirst().currentStatus()).isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test @Disabled("UC-16: cannot view another member's history")
    void givenOtherUserId_whenViewMyHistory_thenRejected() {
        Mockito.when(authenticationService.validateToken(INVALID_TOKEN)).thenReturn(false);
        AuthTokenDTO badAuth = new AuthTokenDTO(INVALID_TOKEN, 0, 0, "hacker");

        PurchaseHistoryDTO result = service.viewMyHistory(badAuth);
        // service swallowed the auth failure and returned an empty history
        assertThat(result.records()).isEmpty();

        // ensure auth was checked and no data access occurred
        Mockito.verify(authenticationService).validateToken(INVALID_TOKEN);
        Mockito.verify(orderReceiptRepository, Mockito.never()).findByHolderUserId(Mockito.anyInt());
        Mockito.verify(ticketRepository, Mockito.never()).findByOrderReceiptId(Mockito.anyInt());
        Mockito.verify(eventRepository, Mockito.never()).findById(Mockito.anyInt());
    }

    @Test @Disabled("UC-16 + II.3.5.2: history reflects price-at-purchase, not current price")
    void givenPriceChangedAfterSale_whenViewMyHistory_thenOriginalPriceShown() {
    }

    // ---- viewMyReceipt (#276): single member-owned receipt; throws instead of degrading ----

    static final int RECEIPT_ID = 7;
    static final int OTHER_USER = 99;

    private static OrderReceipt memberReceipt(int receiptId, int userId) {
        LocalDateTime t = LocalDateTime.of(2026, 6, 1, 12, 0);
        ReceiptLine line = new ReceiptLine(101, 150.00, 2, 1, "15", t);
        return OrderReceipt.forMember(receiptId, userId, 150.00, List.of(line));
    }

    @Test
    void givenOwnReceipt_whenViewMyReceipt_thenRecordReturned() {
        OrderReceipt own = memberReceipt(RECEIPT_ID, USER_ID);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(Optional.of(own));
        Mockito.when(ticketRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(List.of());
        Mockito.when(eventRepository.findById(2)).thenReturn(null);

        PurchaseRecordDTO record = service.viewMyReceipt(VALID_TOKEN, RECEIPT_ID);

        assertThat(record.orderReceiptId()).isEqualTo(RECEIPT_ID);
        assertThat(record.buyerUserId()).isEqualTo(USER_ID);
        assertThat(record.totalPaid()).isEqualByComparingTo(150.00);
        assertThat(record.tickets()).hasSize(1);
        assertThat(record.tickets().getFirst().ticketId()).isEqualTo(101);
    }

    @Test
    void givenInvalidToken_whenViewMyReceipt_thenInvalidTokenThrown() {
        Mockito.when(authenticationService.validateToken(INVALID_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> service.viewMyReceipt(INVALID_TOKEN, RECEIPT_ID))
                .isInstanceOf(InvalidTokenException.class);

        Mockito.verify(orderReceiptRepository, Mockito.never()).findByOrderReceiptId(Mockito.anyInt());
    }

    @Test
    void givenMissingReceipt_whenViewMyReceipt_thenEntityNotFoundThrown() {
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewMyReceipt(VALID_TOKEN, RECEIPT_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void givenAnotherMembersReceipt_whenViewMyReceipt_thenUnauthorizedThrown() {
        OrderReceipt someoneElses = memberReceipt(RECEIPT_ID, OTHER_USER);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.viewMyReceipt(VALID_TOKEN, RECEIPT_ID))
                .isInstanceOf(UnauthorizedActionException.class);

        // ownership rejected before any enrichment work
        Mockito.verify(ticketRepository, Mockito.never()).findByOrderReceiptId(Mockito.anyInt());
    }

    @Test
    void givenGuestReceipt_whenViewMyReceipt_thenUnauthorizedThrown() {
        LocalDateTime t = LocalDateTime.of(2026, 6, 1, 12, 0);
        ReceiptLine line = new ReceiptLine(101, 150.00, 2, 1, "15", t);
        OrderReceipt guestReceipt = OrderReceipt.forGuest("g@example.com", "guest-sess-1", RECEIPT_ID, 150.00, List.of(line));
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(Optional.of(guestReceipt));

        assertThatThrownBy(() -> service.viewMyReceipt(VALID_TOKEN, RECEIPT_ID))
                .isInstanceOf(UnauthorizedActionException.class);
    }
}
