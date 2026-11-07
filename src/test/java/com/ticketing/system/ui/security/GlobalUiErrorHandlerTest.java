package com.ticketing.system.ui.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.CannotCreateTransactionException;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.support.ServiceErrors;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

import ch.qos.logback.classic.Level;

/**
 * {@link GlobalUiErrorHandler} driven as Vaadin drives it: registered through {@code serviceInit}, it sets the
 * error handler of each Vaadin session that starts, and the test hands that handler an uncaught error. A
 * database outage is logged at WARN without its stack and told to the user as an outage; anything else is
 * logged at ERROR with its exception and told to the user in generic words.
 */
class GlobalUiErrorHandlerTest {

    private static final CannotCreateTransactionException DB_OUTAGE =
        new CannotCreateTransactionException("no connection");

    private final ToastRecordingUi ui = new ToastRecordingUi();
    private LogCapture log;

    @BeforeEach
    void setUp() {
        log = LogCapture.of(GlobalUiErrorHandler.class);
    }

    @AfterEach
    void tearDown() {
        log.close();
        UI.setCurrent(null);
    }

    @Test
    void GivenADbOutage_WhenMessageFor_ThenGetsTheTemporarilyUnavailableMessage() {
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, GlobalUiErrorHandler.messageFor(DB_OUTAGE));
    }

    // An uncaught error is a bug whatever its type, so even a refusal the user may read gets the generic text.
    @ParameterizedTest
    @MethodSource("errorsOtherThanAnOutage")
    void GivenAnyOtherError_WhenMessageFor_ThenGetsTheGenericMessage(RuntimeException error) {
        assertEquals(GlobalUiErrorHandler.GENERIC_MESSAGE, GlobalUiErrorHandler.messageFor(error));
    }

    @Test
    void GivenADbOutage_WhenAnErrorReachesTheSessionsHandler_ThenAWarningWithoutTheStackAndTheOutageToast()
            throws Exception {
        UI.setCurrent(ui);

        sessionErrorHandler().error(new ErrorEvent(DB_OUTAGE));

        assertEquals(1, log.messagesAt(Level.WARN).size(), "an outage is logged once at WARN: "
            + log.messagesAt(Level.WARN));
        assertEquals(List.of(), log.throwableClassNamesAt(Level.WARN), "an outage needs no stack in the log");
        assertEquals(List.of(), log.messagesAt(Level.ERROR), "an outage is no bug, so nothing is logged at ERROR");
        assertEquals(List.of(ServiceErrors.DB_UNAVAILABLE_MESSAGE), ui.toasts());
    }

    @Test
    void GivenAnUnexpectedError_WhenAnErrorReachesTheSessionsHandler_ThenAnErrorWithTheExceptionAndTheGenericToast()
            throws Exception {
        UI.setCurrent(ui);

        sessionErrorHandler().error(new ErrorEvent(new IllegalStateException("boom")));

        assertEquals(List.of(IllegalStateException.class.getName()), log.throwableClassNamesAt(Level.ERROR),
            "a bug is logged at ERROR with its exception");
        assertEquals(List.of(), log.messagesAt(Level.WARN));
        assertEquals(List.of(GlobalUiErrorHandler.GENERIC_MESSAGE), ui.toasts());
    }

    @Test
    void GivenNoCurrentUi_WhenAnErrorReachesTheSessionsHandler_ThenItIsLoggedAndNothingThrows() throws Exception {
        ErrorHandler handler = sessionErrorHandler();

        assertDoesNotThrow(() -> handler.error(new ErrorEvent(new IllegalStateException("boom"))));

        assertEquals(List.of(IllegalStateException.class.getName()), log.throwableClassNamesAt(Level.ERROR));
    }

    static Stream<RuntimeException> errorsOtherThanAnOutage() {
        return Stream.of(new IllegalStateException("boom"), new BusinessRuleViolationException("refused"),
            new RuntimeException("internal"));
    }

    // Registers the handler as Vaadin does, starts a Vaadin session, and returns the handler it set on it.
    private static ErrorHandler sessionErrorHandler() throws Exception {
        VaadinService service = mock(VaadinService.class);
        ServiceInitEvent init = mock(ServiceInitEvent.class);
        when(init.getSource()).thenReturn(service);
        new GlobalUiErrorHandler().serviceInit(init);
        ArgumentCaptor<SessionInitListener> sessionStart = ArgumentCaptor.forClass(SessionInitListener.class);
        verify(service).addSessionInitListener(sessionStart.capture());

        VaadinSession session = mock(VaadinSession.class);
        SessionInitEvent started = mock(SessionInitEvent.class);
        when(started.getSession()).thenReturn(session);
        sessionStart.getValue().sessionInit(started);

        ArgumentCaptor<ErrorHandler> handler = ArgumentCaptor.forClass(ErrorHandler.class);
        verify(session).setErrorHandler(handler.capture());
        return handler.getValue();
    }
}
