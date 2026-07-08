package com.ticketing.system.integration;
import com.ticketing.system.identity.domain.Session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.identity.application.port.out.SessionRepository;

/**
 * Regression test for the admin "session expired" bug: {@code AuthenticationService.signInAsAdmin}
 * persists a brand-new {@code Session} row (via {@code generateAdminToken}). It was annotated
 * {@code @Transactional(readOnly = true)}, so under the {@code jpa} profile Hibernate's MANUAL
 * flush mode discarded the assigned-id INSERT — the row was never written and every admin token
 * then failed validation as "session not found", stranding admins on every admin page.
 *
 * <p>This runs under {@code {"test", "jpa"}} on purpose: {@code jpa} swaps in the real
 * {@link com.ticketing.system.identity.adapter.out.persistence.JpaSessionRepository}
 * (the in-memory repo used by the rest of the suite has no flush semantics, which is exactly why
 * the bug slipped through), while {@code test} keeps the in-process external-service stubs and the
 * H2 datasource. Fails before the {@code @Transactional} fix, passes after.
 */
@SpringBootTest
@ActiveProfiles({"test", "jpa"})
class AdminSessionPersistenceJpaTest {

    @Autowired
    private AuthenticationService authService;
    @Autowired
    private SystemAdminService systemAdminService;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void adminSignIn_persistsSessionRow_soTheTokenValidates() {
        // PlatformInitializationRunner is @Profile("!test"), so seed the default admin explicitly
        // (admin/admin from platform.admin.* in application.yml), as acceptance tests do.
        systemAdminService.createDefaultAdminIfMissing();

        AuthTokenDTO token = authService.signInAsAdmin("admin", "admin");
        assertNotNull(token.token());

        // The admin Session row must have been flushed: its token validates and the row is findable.
        assertTrue(authService.validateToken(token.token()),
                "admin token must validate — its backing Session row must be persisted under jpa");
        assertTrue(sessionRepository.findById(sessionManager.extractSessionId(token.token())).isPresent(),
                "admin Session row must be persisted (was lost under @Transactional(readOnly=true))");
    }
}
