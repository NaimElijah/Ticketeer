package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.SessionKind;

/**
 * Regression test for the admin "session expired" bug: {@code AuthenticationService.signInAsAdmin}
 * persists a brand-new {@code Session} row (via {@code generateAdminToken}). It was annotated
 * {@code @Transactional(readOnly = true)}, so on the Jpa adapters Hibernate's MANUAL
 * flush mode discarded the assigned-id INSERT — the row was never written and every admin token
 * then failed validation as "session not found", stranding admins on every admin page.
 *
 * <p>This runs under {@code test} without {@code memory} on purpose: that keeps the real
 * {@link com.ticketing.system.identity.adapter.out.persistence.JpaSessionRepository}
 * (the in-memory repo used by the acceptance suite has no flush semantics, which is exactly why
 * the bug slipped through), while {@code test} adds the in-process external-service stubs and the
 * H2 datasource. Fails before the {@code @Transactional} fix, passes after.
 */
@SpringBootTest
@ActiveProfiles("test")
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
        // An admin token passes the admin readers only; member readers reject it (#185).
        assertTrue(sessionManager.validateAdminToken(token.token()),
                "admin token must validate — its backing Session row must be persisted");
        Optional<Session> row = sessionRepository.findById(sessionManager.extractSessionId(token.token()));
        assertTrue(row.isPresent(),
                "admin Session row must be persisted (was lost under @Transactional(readOnly=true))");
        assertEquals(SessionKind.ADMIN, row.get().getKind(), "the persisted row must record that it is an admin's");
    }
}
