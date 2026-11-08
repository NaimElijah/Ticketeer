package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import com.ticketing.system.sales.adapter.out.wsep.StubPaymentGateway;
import com.ticketing.system.sales.adapter.out.wsep.StubTicketIssuer;

// Fresh context per method so each test starts UNINITIALIZED, with reachable stubs and an
// empty admin repo (the demo seeders are @Profile("dev"), so "test" boots with no admin).
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AdminAcceptanceTest {

    // application.yml's committed defaults for platform.admin, which the test profile keeps.
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    @Autowired SystemAdminService systemAdminService;
    @Autowired AuthenticationService authService;
    @Autowired AdminRepository adminRepository;
    @Autowired StubPaymentGateway stubPaymentGateway;
    @Autowired StubTicketIssuer stubTicketIssuer;

    // BEFORE_EACH_TEST_METHOD leaves the last method's context cached under the shared
    // {test, memory} key, so a method that ends with an unreachable stub would make every
    // later acceptance class fail its platform init.
    @AfterEach
    void restoreReachableStubs() {
        stubPaymentGateway.setReachable(true);
        stubTicketIssuer.setReachable(true);
    }

    // UC-1. READY is initialized but not yet open: only an admin's openMarket opens it.
    @Test
    void GivenValidEnvironment_WhenInitialize_ThenReady() {
        systemAdminService.initializePlatform();

        AuthTokenDTO admin = authService.signInAsAdmin(DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        assertEquals("READY", systemAdminService.viewMarketState(admin.token()).currentStatus());
        assertFalse(systemAdminService.isMarketOpen(), "initialization alone must not open the market");
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
