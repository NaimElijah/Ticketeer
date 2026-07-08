package com.ticketing.system.unit.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.Infrastructure.PlatformInitializationRunner;

class PlatformInitializationRunnerTest {

    @Test
    void whenRun_thenInitializePlatformInvoked() {
        SystemAdminService service = mock(SystemAdminService.class);
        PlatformInitializationRunner runner = new PlatformInitializationRunner(service);

        runner.run(null);

        verify(service).initializePlatform();
    }

    @Test
    void givenInitFails_whenRun_thenSwallowedSoBootContinues() {
        SystemAdminService service = mock(SystemAdminService.class);
        doThrow(new ExternalServiceUnavailableException("no reachable payment service"))
                .when(service).initializePlatform();
        PlatformInitializationRunner runner = new PlatformInitializationRunner(service);

        assertDoesNotThrow(() -> runner.run(null));
    }
}
