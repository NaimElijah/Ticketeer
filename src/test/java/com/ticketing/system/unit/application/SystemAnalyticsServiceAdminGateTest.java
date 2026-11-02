package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.governance.config.AnalyticsProperties;
import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.shared.dto.AdminOverviewDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;

/**
 * The platform analytics ({@link SystemAnalyticsService#computeAnalytics}) and the admin overview
 * ({@link SystemAnalyticsService#adminOverview}) are admin-only: they take the caller's token and pass
 * the admin gate (ADR-012). The application service is the boundary that enforces authentication, not
 * the admin route's capability check, so a member token is rejected however the call is reached.
 *
 * <p>Tokens come from the real {@link JwtSessionManager} over an in-memory session store. The member
 * has the admin's id, since both pools start at 1: only the token's kind tells them apart.
 */
class SystemAnalyticsServiceAdminGateTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final int WINDOW = 5;
    private static final int REFRESH_INTERVAL_MS = 5000;
    private static final long MEMBER_TTL_MINUTES = 60;
    private static final long GUEST_IDLE_MINUTES = 30;
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";
    private static final int SHARED_ID = 1;
    private static final long TOTAL_VISITORS = 12L;
    private static final int ACTIVE_COMPANIES = 0;

    private JwtSessionManager sessionManager;
    private SystemAnalyticsService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        sessionManager = new JwtSessionManager(new JwtProperties(JWT_TEST_SECRET),
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES),
                new MemorySessionRepository(clock), clock);

        ISystemMetrics metrics = mock(ISystemMetrics.class);
        when(metrics.total(MetricType.VISITOR_ENTRY)).thenReturn(TOTAL_VISITORS);
        OrderReceiptRepository orderReceiptRepository = mock(OrderReceiptRepository.class);
        when(orderReceiptRepository.findGlobal(any())).thenReturn(List.of());
        ProductionCompanyRepository companyRepository = mock(ProductionCompanyRepository.class);
        when(companyRepository.findActive()).thenReturn(List.of());
        EventRepository eventRepository = mock(EventRepository.class);
        when(eventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of());
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        when(conversationRepository.findByType(ConversationType.COMPLAINT)).thenReturn(List.of());

        service = new SystemAnalyticsService(metrics, orderReceiptRepository, companyRepository,
                eventRepository, conversationRepository, sessionManager, clock,
                new AnalyticsProperties(WINDOW, REFRESH_INTERVAL_MS));
    }

    @Test
    void GivenMemberToken_WhenComputeAnalytics_ThenRejected() {
        String memberToken = sessionManager.generateToken(SHARED_ID, "member");

        assertThrows(DomainException.class, () -> service.computeAnalytics(memberToken),
                "a member token must not read the platform analytics");
    }

    @Test
    void GivenNoToken_WhenComputeAnalytics_ThenRejected() {
        assertThrows(DomainException.class, () -> service.computeAnalytics(null));
    }

    @Test
    void GivenAdminToken_WhenComputeAnalytics_ThenComputed() {
        String adminToken = sessionManager.generateAdminToken(SHARED_ID, "admin");

        SystemAnalyticsDTO analytics = service.computeAnalytics(adminToken);

        assertEquals(TOTAL_VISITORS, analytics.totalVisitors());
    }

    @Test
    void GivenMemberToken_WhenAdminOverview_ThenRejected() {
        String memberToken = sessionManager.generateToken(SHARED_ID, "member");

        assertThrows(DomainException.class, () -> service.adminOverview(memberToken),
                "a member token must not read the admin overview");
    }

    @Test
    void GivenNoToken_WhenAdminOverview_ThenRejected() {
        assertThrows(DomainException.class, () -> service.adminOverview(null));
    }

    @Test
    void GivenAdminToken_WhenAdminOverview_ThenComputed() {
        String adminToken = sessionManager.generateAdminToken(SHARED_ID, "admin");

        AdminOverviewDTO overview = service.adminOverview(adminToken);

        assertEquals(ACTIVE_COMPANIES, overview.activeCompanies());
    }
}
