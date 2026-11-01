package com.ticketing.system.unit.application;

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
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.catalog.application.port.in.EventDisplayInfoDTO;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.testutil.LogCapture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class MemberAccountServiceTest {

    @Mock AuthenticationService authenticationService;
    @Mock OrderReceiptRepository orderReceiptRepository;
    @Mock TicketRepository ticketRepository;
    @Mock CatalogEventDisplayPort eventDisplayPort;

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
                eventDisplayPort
        );
    }

    // ---- viewMyReceipt (#276): single member-owned receipt; throws instead of degrading ----

    static final int RECEIPT_ID = 7;
    static final int OTHER_USER = 99;

    private static OrderReceipt memberReceipt(int receiptId, int userId) {
        LocalDateTime t = LocalDateTime.of(2026, 6, 1, 12, 0);
        ReceiptLine line = new ReceiptLine(101, 150.00, 2, 1, "15", t);
        return OrderReceipt.forMember(receiptId, userId, 150.00, List.of(line), LocalDateTime.now());
    }

    @Test
    void givenOwnReceipt_whenViewMyReceipt_thenRecordReturned() {
        OrderReceipt own = memberReceipt(RECEIPT_ID, USER_ID);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(Optional.of(own));
        Mockito.when(ticketRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(List.of());
        Mockito.when(eventDisplayPort.describeEvent(2)).thenReturn(null);

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
        OrderReceipt guestReceipt = OrderReceipt.forGuest("g@example.com", "guest-sess-1", RECEIPT_ID, 150.00, List.of(line), LocalDateTime.now());
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(Optional.of(guestReceipt));

        assertThatThrownBy(() -> service.viewMyReceipt(VALID_TOKEN, RECEIPT_ID))
                .isInstanceOf(UnauthorizedActionException.class);
    }

    // ---- Defect 8: member purchase history hides failures (#187, #192) ----

    private static final LocalDateTime SOLD_AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final int EVENT_ID = 2;
    private static final int ZONE_ID = 1;
    private static final int TICKET_ID = 101;
    private static final int LATER_RECEIPT_ID = 8;
    private static final int LATER_TICKET_ID = 102;

    // An empty history on failure tells a member they bought nothing: the failure must reach the caller.
    @Test
    void GivenRepositoryFails_WhenViewMyHistory_ThenErrorIsRaised() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unreachable");
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByHolderUserId(USER_ID)).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.viewMyHistory(memberAuth(VALID_TOKEN, USER_ID)));

        assertThat(thrown).as("the repository failure must propagate, not become an empty history")
                .isSameAs(failure);
    }

    // The session port answers false only for a null or blank token; viewMyReceipt then throws InvalidTokenException.
    @Test
    void GivenBlankToken_WhenViewMyHistory_ThenInvalidTokenThrown() {
        Mockito.when(authenticationService.validateToken("  ")).thenReturn(false);

        assertThatThrownBy(() -> service.viewMyHistory(memberAuth("  ", USER_ID)))
                .isInstanceOf(InvalidTokenException.class);

        Mockito.verify(orderReceiptRepository, Mockito.never()).findByHolderUserId(Mockito.anyInt());
    }

    @Test
    void GivenMalformedToken_WhenViewMyHistory_ThenInvalidTokenThrown() {
        InvalidTokenException rejected = new InvalidTokenException("malformed token");
        Mockito.when(authenticationService.validateToken(INVALID_TOKEN)).thenThrow(rejected);

        assertThatThrownBy(() -> service.viewMyHistory(memberAuth(INVALID_TOKEN, USER_ID)))
                .isSameAs(rejected);

        Mockito.verify(orderReceiptRepository, Mockito.never()).findByHolderUserId(Mockito.anyInt());
    }

    @Test
    void GivenExpiredToken_WhenViewMyHistory_ThenSessionExpiredThrown() {
        SessionExpiredException expired = new SessionExpiredException();
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenThrow(expired);

        assertThatThrownBy(() -> service.viewMyHistory(memberAuth(VALID_TOKEN, USER_ID)))
                .isSameAs(expired);
    }

    @Test
    void GivenAuthenticatedMember_WhenViewMyHistory_ThenOwnHistoryReturned() {
        OrderReceipt own = soldReceipt(RECEIPT_ID, USER_ID, TICKET_ID, 150.00);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByHolderUserId(USER_ID)).thenReturn(List.of(own));
        Mockito.when(ticketRepository.findByOrderReceiptId(RECEIPT_ID))
                .thenReturn(List.of(issuedTicket(RECEIPT_ID, TICKET_ID, 150.00)));
        Mockito.when(eventDisplayPort.describeEvent(EVENT_ID)).thenReturn(rockNight());

        PurchaseHistoryDTO history = service.viewMyHistory(memberAuth(VALID_TOKEN, USER_ID));

        assertThat(history.records()).hasSize(1);
        PurchaseRecordDTO record = history.records().getFirst();
        assertThat(record.orderReceiptId()).isEqualTo(RECEIPT_ID);
        assertThat(record.buyerUserId()).isEqualTo(USER_ID);
        assertThat(record.totalPaid()).isEqualByComparingTo(150.00);
        assertThat(record.tickets()).singleElement().satisfies(line -> {
            assertThat(line.ticketId()).isEqualTo(TICKET_ID);
            assertThat(line.currentStatus()).isEqualTo(TicketStatus.PAID);
            assertThat(line.eventName()).isEqualTo("Rock Night");
            assertThat(line.zoneName()).isEqualTo("Floor");
            assertThat(line.barcode()).isEqualTo("BARCODE-" + TICKET_ID);
        });
    }

    // The AuthTokenDTO carries a member id a caller can set to anyone. Decided (#192): it is ignored, as
    // viewMyReceipt reads only the token, so the history is the token's member's, never the named one's.
    @Test
    void GivenTokenDtoNamingAnotherMember_WhenViewMyHistory_ThenTheTokensMemberHistoryIsReturned() {
        AuthTokenDTO forged = new AuthTokenDTO(VALID_TOKEN, 1000, OTHER_USER, "someone-else");
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByHolderUserId(USER_ID))
                .thenReturn(List.of(soldReceipt(RECEIPT_ID, USER_ID, TICKET_ID, 150.00)));
        Mockito.when(ticketRepository.findByOrderReceiptId(RECEIPT_ID))
                .thenReturn(List.of(issuedTicket(RECEIPT_ID, TICKET_ID, 150.00)));
        Mockito.when(eventDisplayPort.describeEvent(EVENT_ID)).thenReturn(rockNight());

        PurchaseHistoryDTO history = service.viewMyHistory(forged);

        assertThat(history.records()).singleElement().satisfies(record -> {
            assertThat(record.orderReceiptId()).isEqualTo(RECEIPT_ID);
            assertThat(record.buyerUserId()).isEqualTo(USER_ID);
        });
        Mockito.verify(orderReceiptRepository, Mockito.never()).findByHolderUserId(OTHER_USER);
    }

    // As viewMyReceipt refuses a null token: a missing credential is an invalid one.
    @Test
    void GivenNullAuthToken_WhenViewMyHistory_ThenInvalidTokenThrown() {
        assertThatThrownBy(() -> service.viewMyHistory(null)).isInstanceOf(InvalidTokenException.class);

        Mockito.verify(orderReceiptRepository, Mockito.never()).findByHolderUserId(Mockito.anyInt());
    }

    // UC-16 + II.3.5.2: a zone repriced after a sale leaves that sale's record at the price paid. The later
    // sale of the same zone at the new price shows that price, so neither record reads a current price.
    @Test
    void GivenPriceChangedAfterSale_WhenViewMyHistory_ThenOriginalPriceShown() {
        OrderReceipt beforeChange = soldReceipt(RECEIPT_ID, USER_ID, TICKET_ID, 150.00);
        OrderReceipt afterChange = soldReceipt(LATER_RECEIPT_ID, USER_ID, LATER_TICKET_ID, 120.00);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByHolderUserId(USER_ID)).thenReturn(List.of(beforeChange, afterChange));
        Mockito.when(ticketRepository.findByOrderReceiptId(RECEIPT_ID))
                .thenReturn(List.of(issuedTicket(RECEIPT_ID, TICKET_ID, 150.00)));
        Mockito.when(ticketRepository.findByOrderReceiptId(LATER_RECEIPT_ID))
                .thenReturn(List.of(issuedTicket(LATER_RECEIPT_ID, LATER_TICKET_ID, 120.00)));
        Mockito.when(eventDisplayPort.describeEvent(EVENT_ID)).thenReturn(rockNight());

        PurchaseHistoryDTO history = service.viewMyHistory(memberAuth(VALID_TOKEN, USER_ID));

        assertThat(history.records())
                .extracting(PurchaseRecordDTO::orderReceiptId, PurchaseRecordDTO::totalPaid)
                .containsExactly(tuple(RECEIPT_ID, 150.00), tuple(LATER_RECEIPT_ID, 120.00));
        assertThat(history.records())
                .extracting(record -> record.tickets().getFirst().pricePaid())
                .containsExactly(150.00, 120.00);
    }

    private static AuthTokenDTO memberAuth(String token, int userId) {
        return new AuthTokenDTO(token, 1000, userId, "member" + userId);
    }

    private static OrderReceipt soldReceipt(int receiptId, int userId, int ticketId, double price) {
        ReceiptLine line = new ReceiptLine(ticketId, price, EVENT_ID, ZONE_ID, null, SOLD_AT);
        return OrderReceipt.forMember(receiptId, userId, price, List.of(line), SOLD_AT);
    }

    private static Ticket issuedTicket(int receiptId, int ticketId, double price) {
        return new Ticket(EVENT_ID, ZONE_ID, receiptId, null, price, ticketId, "BARCODE-" + ticketId);
    }

    private static EventDisplayInfoDTO rockNight() {
        return new EventDisplayInfoDTO(EVENT_ID, "Rock Night", 10, "CONCERT", null, "Venue",
                List.of(new EventDisplayInfoDTO.ZoneNameDTO(ZONE_ID, "Floor")));
    }

    // ---- Review follow-up 6b: a history read is no state transition, so it logs nothing at INFO (#187) ----

    @Test
    void GivenAuthenticatedMember_WhenViewMyHistory_ThenNothingIsLoggedAtInfo() {
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByHolderUserId(USER_ID)).thenReturn(List.of());

        try (LogCapture log = LogCapture.of(MemberAccountService.class)) {
            service.viewMyHistory(memberAuth(VALID_TOKEN, USER_ID));

            assertThat(log.messagesAt(Level.INFO)).isEmpty();
        }
    }
}
