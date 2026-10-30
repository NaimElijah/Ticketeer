package com.ticketing.system.ui.session;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.vaadin.flow.server.VaadinSession;

/**
 * Vaadin's {@code CurrentInstance} holds the current session through a weak reference. A helper that
 * installs a mock as the current session must therefore keep it reachable itself, or a garbage
 * collection between arranging a test and running it leaves no current session, and the test fails
 * only when the JVM happens to collect, as a full CI run did.
 */
class MapBackedVaadinSessionTest {

    // Each System.gc() is a request, so ask a few times; HotSpot honours the first in practice.
    private static final int GC_REQUESTS = 5;

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenAnInstalledSession_WhenGarbageIsCollected_ThenItIsStillTheCurrentSessionWithItsState() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, 1, "member"));
        // Mockito keeps the last-invoked mock reachable from a thread-local. A test goes on to stub
        // other mocks, which moves that reference, so do the same here.
        mock(Runnable.class).run();

        for (int i = 0; i < GC_REQUESTS; i++) {
            System.gc();
        }

        assertNotNull(VaadinSession.getCurrent(), "the installed session must survive a garbage collection");
        assertTrue(AuthSession.isSignedIn(), "and keep the state the test arranged");
    }
}
