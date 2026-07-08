package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.sales.adapter.out.wsep.StubPaymentGateway;
import com.ticketing.system.sales.adapter.out.wsep.StubTicketIssuer;

// Fresh context per method so each test starts UNINITIALIZED, with reachable stubs and an
// empty admin repo (the demo seeders are @Profile("dev"), so "test" boots with no admin).
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AdminAcceptanceTest {

    @Autowired SystemAdminService systemAdminService;
    @Autowired AdminRepository adminRepository;
    @Autowired StubPaymentGateway stubPaymentGateway;
    @Autowired StubTicketIssuer stubTicketIssuer;

    // UC-1
    @Test
    void GivenValidEnvironment_WhenInitialize_ThenReady() {
        systemAdminService.initializePlatform();
        assertTrue(adminRepository.existsAny());
    }

    @Test
    void GivenNoGateway_WhenInitialize_ThenFails() {
        stubPaymentGateway.setReachable(false);
        assertThrows(ExternalServiceUnavailableException.class,
                () -> systemAdminService.initializePlatform());
    }

    @Test
    void GivenNoIssuer_WhenInitialize_ThenFails() {
        stubTicketIssuer.setReachable(false);
        assertThrows(ExternalServiceUnavailableException.class,
                () -> systemAdminService.initializePlatform());
    }

    @Test
    void GivenNoAdmin_WhenInitialize_ThenDefaultCreated() {
        assertFalse(adminRepository.existsAny());
        systemAdminService.initializePlatform();
        assertTrue(adminRepository.existsAny());
    }

    // UC-32
    @Test @Disabled("UC-32 main: openMarket re-verifies + opens")
    void GivenInitialized_WhenOpenMarket_ThenOpen() {}
    @Test @Disabled("UC-32 negative: gateway down → market does not open (I.2.2)")
    void GivenGatewayDown_WhenOpenMarket_ThenRejected() {}
    @Test @Disabled("UC-32 alt: idempotent — already-open is no-op")
    void GivenAlreadyOpen_WhenOpenMarket_ThenNoop() {}

    // UC-31
    @Test @Disabled("UC-31 main: admin views global purchase history")
    void GivenAdmin_WhenViewGlobalHistory_ThenAllSales() {}
    @Test @Disabled("UC-31 main: filter by buyer / company / event")
    void GivenAdmin_WhenFilter_ThenScoped() {}
    @Test @Disabled("UC-31 negative: non-admin rejected")
    void GivenNonAdmin_WhenViewGlobalHistory_ThenRejected() {}
}
