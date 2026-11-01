package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.catalog.application.port.in.EventDisplayInfoDTO;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dtoMappers.OrderReceiptMapper;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.UserNotFoundException;

/**
 * {@link OrderReceiptMapper} renders a receipt's event, zone, company and buyer names, each a
 * best-effort lookup (#187, SLR.5). A record that no longer exists renders as a null name, each port
 * saying so its own way: the event display port returns {@code null}, the company and user
 * repositories throw their not-found exceptions. A failure of the store behind a lookup is not a
 * missing record, and must reach the caller rather than render as one.
 */
class OrderReceiptMapperFailureTest {

    private static final LocalDateTime PURCHASED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final int RECEIPT_ID = 7;
    private static final int BUYER_ID = 42;
    private static final int EVENT_ID = 10;
    private static final int ZONE_ID = 3;
    private static final int COMPANY_ID = 100;
    private static final int TICKET_ID = 101;
    private static final int PAYMENT_TX = 555;
    private static final double PRICE = 150.0;
    private static final String EVENT_NAME = "Concert";
    private static final String ZONE_NAME = "Floor";
    private static final String COMPANY_NAME = "Acme Shows";
    private static final String BUYER_NAME = "buyer42";

    private final OrderReceiptMapper mapper = new OrderReceiptMapper();
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final CatalogEventDisplayPort eventDisplay = mock(CatalogEventDisplayPort.class);
    private final ProductionCompanyRepository companyRepository = mock(ProductionCompanyRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final OrderReceipt receipt = OrderReceipt.forMember(RECEIPT_ID, BUYER_ID, PRICE,
            List.of(new ReceiptLine(TICKET_ID, PRICE, EVENT_ID, ZONE_ID, null, PURCHASED_AT)), PURCHASED_AT,
            List.of(TransactionRecord.paymentCharge(PAYMENT_TX, "stub", PRICE, "ILS", PURCHASED_AT)));

    @BeforeEach
    void everyRecordExists() {
        when(ticketRepository.findByOrderReceiptId(RECEIPT_ID)).thenReturn(List.of());
        when(eventDisplay.describeEvent(EVENT_ID)).thenReturn(new EventDisplayInfoDTO(EVENT_ID, EVENT_NAME, COMPANY_ID,
                "CONCERT", ZonedDateTime.of(PURCHASED_AT.plusDays(10), ZoneOffset.UTC), "Tel Aviv",
                List.of(new EventDisplayInfoDTO.ZoneNameDTO(ZONE_ID, ZONE_NAME))));
        ProductionCompany company = mock(ProductionCompany.class);
        when(company.getName()).thenReturn(COMPANY_NAME);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        User buyer = mock(User.class);
        when(buyer.getUsername()).thenReturn(BUYER_NAME);
        when(userRepository.getUserById(BUYER_ID)).thenReturn(buyer);
    }

    // ---- a store failure propagates ----

    @Test
    void GivenTheEventStoreDown_WhenMappingAReceipt_ThenTheStoreFailurePropagates() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        when(eventDisplay.describeEvent(EVENT_ID)).thenThrow(storeDown);

        assertSame(storeDown, assertThrows(RuntimeException.class, this::map),
                "an event lookup that failed must not render as a missing event");
    }

    @Test
    void GivenTheCompanyStoreDown_WhenMappingAReceipt_ThenTheStoreFailurePropagates() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        when(companyRepository.getCompanyById(COMPANY_ID)).thenThrow(storeDown);

        assertSame(storeDown, assertThrows(RuntimeException.class, this::map),
                "a company lookup that failed must not render as a missing company");
    }

    @Test
    void GivenTheUserStoreDown_WhenMappingAReceipt_ThenTheStoreFailurePropagates() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        when(userRepository.getUserById(BUYER_ID)).thenThrow(storeDown);

        assertSame(storeDown, assertThrows(RuntimeException.class, this::map),
                "a buyer lookup that failed must not render as a missing buyer");
    }

    // ---- guards: a missing record renders as a null name ----

    @Test
    void GivenEveryRecordExists_WhenMappingAReceipt_ThenEveryNameIsRendered() {
        PurchaseHistoryDTO.PurchaseRecordDTO record = map();

        PurchaseHistoryDTO.TicketRecordDTO line = record.tickets().get(0);
        assertAll(
                () -> assertEquals(EVENT_NAME, line.eventName()),
                () -> assertEquals(ZONE_NAME, line.zoneName()),
                () -> assertEquals(COMPANY_NAME, line.companyName()),
                () -> assertEquals(BUYER_NAME, record.buyerName()));
    }

    @Test
    void GivenAnEventThatNoLongerExists_WhenMappingAReceipt_ThenItsNamesRenderAsNull() {
        when(eventDisplay.describeEvent(EVENT_ID)).thenReturn(null);

        PurchaseHistoryDTO.TicketRecordDTO line = map().tickets().get(0);

        assertAll(
                () -> assertNull(line.eventName(), "a missing event has no name"),
                () -> assertNull(line.zoneName(), "a missing event has no zone name"),
                () -> assertNull(line.companyName(), "a missing event names no company"));
    }

    @Test
    void GivenACompanyThatNoLongerExists_WhenMappingAReceipt_ThenOnlyItsNameRendersAsNull() {
        when(companyRepository.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        PurchaseHistoryDTO.TicketRecordDTO line = map().tickets().get(0);

        assertAll(
                () -> assertNull(line.companyName(), "a missing company has no name"),
                () -> assertEquals(EVENT_NAME, line.eventName(), "the event is still named"));
    }

    @Test
    void GivenABuyerWhoNoLongerExists_WhenMappingAReceipt_ThenOnlyTheBuyerNameRendersAsNull() {
        when(userRepository.getUserById(BUYER_ID)).thenThrow(new UserNotFoundException(BUYER_ID));

        PurchaseHistoryDTO.PurchaseRecordDTO record = map();

        assertAll(
                () -> assertNull(record.buyerName(), "a missing buyer has no name"),
                () -> assertEquals(EVENT_NAME, record.tickets().get(0).eventName(), "the event is still named"));
    }

    private PurchaseHistoryDTO.PurchaseRecordDTO map() {
        return mapper.toPurchaseRecordDTO(receipt, ticketRepository, eventDisplay, companyRepository, userRepository);
    }
}
