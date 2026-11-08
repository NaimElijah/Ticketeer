package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.session.CompanyManagementBridge;

/**
 * {@link CompanyManagementBridge} is static, and a Spring context closed by an earlier test class leaves
 * its dead service in it. A view test that reached that service failed with "EntityManagerFactory is
 * closed", but only when a {@code @DirtiesContext} class happened to run first (#189).
 */
class ViewHarnessBridgeIsolationTest {

    private final CompanyManagementService leftBehind = mock(CompanyManagementService.class);
    private CompanyManagementService bridgedBefore;

    @BeforeEach
    void leaveAServiceInTheBridge() {
        bridgedBefore = CompanyManagementBridge.service();
        new CompanyManagementBridge(leftBehind);
    }

    @AfterEach
    void restoreTheBridge() {
        new CompanyManagementBridge(bridgedBefore);
    }

    @Test
    void GivenAServiceLeftInTheBridge_WhenAMembersCapabilitiesAreReadInAHarness_ThenThatServiceIsNeverCalled() {
        try (ViewHarness ignored = new ViewHarness()) {
            ViewHarness.signInMember();

            Capabilities.forCurrentUser();
        }

        verifyNoInteractions(leftBehind);
    }

    @Test
    void GivenAHarnessThatGaveMemberships_WhenItCloses_ThenTheBridgeHoldsTheServiceLeftBehind() {
        try (ViewHarness harness = new ViewHarness()) {
            harness.memberships();
        }

        assertSame(leftBehind, CompanyManagementBridge.service());
    }

    @Test
    void GivenAHarnessThatGaveNoMemberships_WhenItCloses_ThenTheBridgeHoldsTheServiceLeftBehind() {
        try (ViewHarness ignored = new ViewHarness()) {
            ViewHarness.signInMember();
        }

        assertSame(leftBehind, CompanyManagementBridge.service());
    }
}
