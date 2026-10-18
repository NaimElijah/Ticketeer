package com.ticketing.system.unit.infrastructure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.InitializationIntegrityException;
import com.ticketing.system.shared.exception.MissingDefaultAdminException;
import com.ticketing.system.shared.exception.SystemIntegrityViolationException;
import com.ticketing.system.bootstrap.PlatformInitializationRunner;

class PlatformInitializationRunnerTest {

    @Test
    void whenRun_thenInitializePlatformInvoked() {
        SystemAdminService service = mock(SystemAdminService.class);
        PlatformInitializationRunner runner = new PlatformInitializationRunner(service);

        runner.run(null);

        verify(service).initializePlatform();
    }

    @Test
    void GivenWsepUnreachable_WhenRun_ThenSwallowedSoBootContinues() {
        SystemAdminService service = mock(SystemAdminService.class);
        doThrow(new ExternalServiceUnavailableException("no reachable payment service"))
                .when(service).initializePlatform();
        PlatformInitializationRunner runner = new PlatformInitializationRunner(service);

        assertDoesNotThrow(() -> runner.run(null));
    }

    // ADR-003: only a WSEP outage heals by waiting; every other init failure must stop the boot.
    static Stream<DomainException> fatalInitFailures() {
        return Stream.of(
                new MissingDefaultAdminException("default admin was not persisted"),
                new InitializationIntegrityException("no System Admin present"),
                new SystemIntegrityViolationException("active company 7 has no owner"));
    }

    @ParameterizedTest
    @MethodSource("fatalInitFailures")
    void GivenFatalInitFailure_WhenRun_ThenPropagatesSoBootFails(DomainException failure) {
        SystemAdminService service = mock(SystemAdminService.class);
        doThrow(failure).when(service).initializePlatform();
        PlatformInitializationRunner runner = new PlatformInitializationRunner(service);

        DomainException thrown = assertThrows(DomainException.class, () -> runner.run(null));

        assertSame(failure, thrown);
    }
}
