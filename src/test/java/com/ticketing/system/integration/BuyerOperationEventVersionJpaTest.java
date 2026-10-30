package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;

/**
 * Buyer operations leave the event's and the company's own rows alone (SLR.1, SLR.4). Event and
 * ProductionCompany carry a {@code @Version} for structural and lifecycle edits, while seats and zones
 * carry their own, so that buyers reserving or buying in one event never conflict on the event itself.
 * A reservation or a checkout that rewrote the event row would bump its version, and every other buyer
 * operation on that event committing at the same time would then fail, a checkout only after its
 * charge. Runs on the Jpa adapters over H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class BuyerOperationEventVersionJpaTest {

    private static final String SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    private SeatedShowFixture fixture;
    private Show show;
    private AuthTokenDTO member;

    @BeforeEach
    void publishShow() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
        member = fixture.registerAndLogin("versionBuyer");
    }

    @Test
    void GivenAPublishedShow_WhenAMemberReservesASeat_ThenTheEventAndCompanyVersionsAreUnchanged() {
        long eventVersion = eventVersion();
        long companyVersion = companyVersion();

        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(SEAT)));

        assertVersionsUnchanged(eventVersion, companyVersion);
    }

    @Test
    void GivenAReservedSeat_WhenTheMemberChecksOut_ThenTheEventAndCompanyVersionsAreUnchanged() {
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(SEAT)));
        long eventVersion = eventVersion();
        long companyVersion = companyVersion();

        checkoutService.checkoutMember(member.token(), "version-" + UUID.randomUUID(), CURRENCY, CARD);

        assertVersionsUnchanged(eventVersion, companyVersion);
    }

    private void assertVersionsUnchanged(long eventVersion, long companyVersion) {
        assertAll(
                () -> assertEquals(eventVersion, eventVersion(), "a buyer operation must not rewrite the event row"),
                () -> assertEquals(companyVersion, companyVersion(),
                        "a buyer operation must not rewrite the company row"));
    }

    private long eventVersion() {
        return jdbc.queryForObject("select version from events where id = ?", Long.class, show.eventId());
    }

    private long companyVersion() {
        return jdbc.queryForObject("select c.version from companies c join events e on e.company_id = c.company_id"
                + " where e.id = ?", Long.class, show.eventId());
    }
}
