package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.BcryptPasswordHasher;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.config.IdentityProperties;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.ReservedUsernameException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.testutil.AdjustableClock;
import com.ticketing.system.testutil.TestTransactions;
import com.ticketing.system.testutil.LogCapture;

class AuthenticationServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long GUEST_IDLE_MINUTES = 30;
    private static final long MEMBER_TTL_MINUTES = 1440;
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";
    private static final IdentityProperties IDENTITY =
            new IdentityProperties(List.of("admin", "platform.admin"), "admin");
    private static final String HASH_FROM_THE_HASHER = "$2a$10$hash-the-mock-hasher-returns-for-any-input";

    private UserRepository mockUserRepo;
    private PasswordHasher mockHasher;
    private SessionManager mockSessionManager;
    private SessionRepository mockSessionRepo;
    private AdminRepository mockAdminRepo;
    private Clock fixedClock;
    private AuthenticationService service;
    private NotificationDispatchService mockNotification;
    private CartRestorationPort mockCartRestorationPort;

    @BeforeEach
    void setUp() {
        mockUserRepo = mock(UserRepository.class);
        mockHasher = mock(PasswordHasher.class);
        mockSessionManager = mock(SessionManager.class);
        mockNotification = mock(NotificationDispatchService.class);
        mockCartRestorationPort = mock(CartRestorationPort.class);
        mockSessionRepo = mock(SessionRepository.class);
        mockAdminRepo = mock(AdminRepository.class);
        fixedClock = Clock.fixed(T0, ZoneOffset.UTC);
        // The service may hash a throwaway secret when it is built (its dummy hash for unknown
        // usernames), so the mock answers with a hash and forgets that call: each test sees only the
        // hasher calls its own action makes.
        when(mockHasher.hash(any())).thenReturn(HASH_FROM_THE_HASHER);
        service = new AuthenticationService(
                mockUserRepo, mockHasher, mockSessionManager, mockCartRestorationPort, mockNotification,
                mockSessionRepo, mock(ISystemMetrics.class),
                fixedClock, mockAdminRepo,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), new LockoutProperties(5, 15L),
                IDENTITY, TestTransactions.noOpManager());
        clearInvocations(mockHasher);
    }

    /**
     * Returns a valid Guest session and mocks sessionRepo.findById(sid) to return
     * it.
     */
    private Session mockValidGuestSession(String sid) {
        Instant expiry = T0.plus(GUEST_IDLE_MINUTES, ChronoUnit.MINUTES);
        Session guest = new Session(sid, null, T0, expiry);
        when(mockSessionRepo.findById(sid)).thenReturn(Optional.of(guest));
        return guest;
    }

    // ----------------------------------------------------------------------
    // startGuestSession
    // ----------------------------------------------------------------------

    @Test
    void startGuestSession_returnsDtoWithNonBlankId() {
        GuestSessionDTO dto = service.startGuestSession();
        assertNotNull(dto.sessionId());
        assertTrue(!dto.sessionId().isBlank());
        assertEquals(T0, dto.createdAt());
    }

    @Test
    void startGuestSession_savesGuestRow() {
        service.startGuestSession();
        ArgumentCaptor<Session> captured = ArgumentCaptor.forClass(Session.class);
        verify(mockSessionRepo, times(1)).save(captured.capture());
        Session s = captured.getValue();
        assertTrue(s.isGuest());
        assertEquals(T0, s.getCreatedAt());
    }

    @Test
    void startGuestSession_eachCallProducesDistinctIds() {
        GuestSessionDTO a = service.startGuestSession();
        GuestSessionDTO b = service.startGuestSession();
        assertNotEquals(a.sessionId(), b.sessionId());
    }

    // ----------------------------------------------------------------------
    // endGuestSession
    // ----------------------------------------------------------------------

    @Test
    void endGuestSession_deletesRow() {
        mockValidGuestSession("guest-1");

        service.endGuestSession("guest-1");
        verify(mockSessionRepo, times(1)).delete("guest-1");
    }

    @Test
    void endGuestSession_nullIsNoOp() {
        service.endGuestSession(null);
        verify(mockSessionRepo, never()).delete(any());
    }

    @Test
    void endGuestSession_blankIsNoOp() {
        service.endGuestSession("   ");
        verify(mockSessionRepo, never()).delete(any());
    }

    // ----------------------------------------------------------------------
    // Register — format validation runs first (no session mock needed)
    // ----------------------------------------------------------------------

    @Test
    void givenMalformedEmail_whenRegister_thenInvalidEmailFormatExceptionThrown() {
        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", "not-an-email", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
        verify(mockSessionRepo, never()).findById(any());
    }

    @Test
    void givenNullEmail_whenRegister_thenInvalidEmailFormatExceptionThrown() {
        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", null, "Password1", "guest-1", 56)));
    }

    @Test
    void givenShortPassword_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Pw1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenPasswordWithoutDigit_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "Passwords", "guest-1", 56)));
    }

    @Test
    void givenPasswordWithoutLetter_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "12345678", "guest-1", 56)));
    }

    @Test
    void givenNullPassword_whenRegister_thenWeakPasswordExceptionThrown() {
        assertThrows(WeakPasswordException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", null, "guest-1", 56)));
    }

    // ----------------------------------------------------------------------
    // Register — session validation (after format check)
    // ----------------------------------------------------------------------

    @Test
    void givenNoGuestSessionId_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", null, 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenBlankGuestSessionId_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "   ", 56)));
    }

    @Test
    void givenUnknownGuestSessionId_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        when(mockSessionRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "ghost", 56)));
    }

    @Test
    void givenMemberSessionIdAsGuest_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        Session memberSession = new Session("sid", 99, T0, T0.plusSeconds(3600));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(memberSession));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "sid", 56)));
    }

    @Test
    void givenExpiredGuestSession_whenRegister_thenGuestSessionRequiredExceptionThrown() {
        Session expired = new Session("sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(expired));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "sid", 56)));
    }

    // ----------------------------------------------------------------------
    // Register — happy path
    // ----------------------------------------------------------------------

    @Test
    void givenRegistrationData_whenRegister_thenUserCreatedWithHashedPassword() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(mockUserRepo.nextId()).thenReturn(42);
        when(mockHasher.hash("Password1")).thenReturn("HASHED");

        service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56));

        verify(mockHasher, times(1)).hash("Password1");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(mockUserRepo, times(1)).save(savedUser.capture());
        assertEquals(42, savedUser.getValue().getUserId());
        assertEquals("alice", savedUser.getValue().getUsername());
        assertEquals("alice@example.com", savedUser.getValue().getEmail());
    }

    @Test
    void givenSuccessfulRegistration_whenCheckSession_thenStillGuest() {
        Session guest = mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername(any())).thenReturn(false);
        when(mockUserRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(mockUserRepo.nextId()).thenReturn(1);
        when(mockHasher.hash(any())).thenReturn("HASHED");

        service.register(new RegisterRequestDTO("bob", "bob@example.com", "Password1", "guest-1", 56));

        // II.1.4 / D10a: session must remain Guest after register.
        assertTrue(guest.isGuest());
        // No JWT issued — session-establishing collaborators not invoked.
        verify(mockSessionManager, never()).generateToken(anyInt(), any());
        verify(mockSessionManager, never()).generateTokenForSession(any(), any());
    }

    @Test
    void givenTakenUsername_whenRegister_thenDuplicateUsernameExceptionThrown() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(true);

        assertThrows(DuplicateUsernameException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenTakenEmail_whenRegister_thenDuplicateEmailExceptionThrown() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(new User(1, "other", "alice@example.com", "HASH", 59)));

        assertThrows(DuplicateEmailException.class,
                () -> service
                        .register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    // Two registrations can both pass existsByUsername; the repository refuses the second at save
    // (#185), and register must pass that refusal on without touching the guest session.
    @Test
    void GivenUsernameTakenAfterTheCheck_WhenRegister_ThenTheRepositorysDuplicateUsernameExceptionPropagates() {
        Session guest = mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail("alice@example.com")).thenReturn(Optional.empty());
        when(mockUserRepo.nextId()).thenReturn(42);
        when(mockHasher.hash("Password1")).thenReturn("HASHED");
        DuplicateUsernameException refusal = new DuplicateUsernameException("alice");
        doThrow(refusal).when(mockUserRepo).save(any(User.class));

        DuplicateUsernameException thrown = assertThrows(DuplicateUsernameException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", "Password1", "guest-1", 56)));

        assertEquals(refusal, thrown);
        verify(mockSessionRepo, never()).save(guest);
    }

    // ----------------------------------------------------------------------
    // Register — the username is checked before any I/O (#187). Accounts are looked up by the exact
    // name, so a padded name would register a look-alike of an existing account; it is refused up
    // front, as a blank one is, with a message the user can read (ServiceErrors.userMessage shows
    // only a DomainException, IllegalArgumentException or IllegalStateException), before any
    // repository call or password hash.
    // ----------------------------------------------------------------------

    @Test
    void GivenBobExists_WhenRegisterBobWithALeadingSpace_ThenRefusedBeforeAnyRepositoryCallOrHash() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("bob")).thenReturn(true);
        when(mockUserRepo.nextId()).thenReturn(42);

        assertThrows(IllegalArgumentException.class,
                () -> service.register(new RegisterRequestDTO(" bob", "bob2@example.com", "Password1", "guest-1", 56)),
                "' bob' would register a look-alike of the existing account bob");

        verifyNoInteractions(mockUserRepo);
        verifyNoInteractions(mockHasher);
    }

    @Test
    void GivenBobExists_WhenRegisterBobWithATrailingSpace_ThenRefusedBeforeAnyRepositoryCallOrHash() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("bob")).thenReturn(true);
        when(mockUserRepo.nextId()).thenReturn(42);

        assertThrows(IllegalArgumentException.class,
                () -> service.register(new RegisterRequestDTO("bob ", "bob2@example.com", "Password1", "guest-1", 56)),
                "'bob ' would register a look-alike of the existing account bob");

        verifyNoInteractions(mockUserRepo);
        verifyNoInteractions(mockHasher);
    }

    @Test
    void GivenBlankUsername_WhenRegister_ThenRefusedBeforeAnyRepositoryCallOrHash() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.nextId()).thenReturn(42);

        assertThrows(IllegalArgumentException.class,
                () -> service.register(new RegisterRequestDTO("   ", "blank@example.com", "Password1", "guest-1", 56)));

        verifyNoInteractions(mockUserRepo);
        verifyNoInteractions(mockHasher);
    }

    // ----------------------------------------------------------------------
    // Register — the username is limited to the characters the UI allows, [A-Za-z0-9._-] (#187).
    // RegisterView submits whatever its field holds, and trim() strips only characters up to U+0020,
    // so a name the live check refuses (a no-break or zero-width space, a Cyrillic look-alike, an
    // inner space, a bidi override) would still register a look-alike of another account. The length
    // (3–32 in the UI) is not enforced here: that is open question OQ-8.
    // ----------------------------------------------------------------------

    static Stream<Arguments> usernamesOutsideTheAllowedCharacters() {
        return Stream.of(
                Arguments.of("a trailing no-break space", "bob\u00A0"),
                Arguments.of("only no-break spaces", "\u00A0\u00A0\u00A0"),
                Arguments.of("a trailing zero-width space", "bob\u200B"),
                Arguments.of("a Cyrillic a in admin", "\u0430dmin"),
                Arguments.of("an inner space", "TicketHub Support"),
                Arguments.of("a right-to-left override", "\u202Enimda"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("usernamesOutsideTheAllowedCharacters")
    void GivenUsernameOutsideTheAllowedCharacters_WhenRegister_ThenRefusedBeforeAnyRepositoryCallOrHash(
            String description, String username) {
        givenRegistrationWillSucceed("guest-1");
        clearInvocations(mockHasher);

        assertThrows(IllegalArgumentException.class,
                () -> service.register(new RegisterRequestDTO(username, "new@example.com", "Password1", "guest-1", 56)),
                "a username with " + description + " is outside [A-Za-z0-9._-] and must be refused");

        verifyNoInteractions(mockUserRepo);
        verifyNoInteractions(mockSessionRepo);
        verifyNoInteractions(mockHasher);
    }

    @Test
    void GivenUsernameOfEveryAllowedCharacterKind_WhenRegister_ThenRegistered() {
        givenRegistrationWillSucceed("guest-1");

        service.register(new RegisterRequestDTO("alice.b-2_x", "alice@example.com", "Password1", "guest-1", 56));

        verify(mockUserRepo, times(1)).save(argThat(user -> "alice.b-2_x".equals(user.getUsername())));
    }

    // The UI asks for 3 to 32 characters, but register does not enforce a length (OQ-8), and the
    // review scenario registers two-character names.
    @Test
    void GivenTwoCharacterUsername_WhenRegister_ThenRegistered() {
        givenRegistrationWillSucceed("guest-1");

        service.register(new RegisterRequestDTO("u1", "u1@example.com", "Password1", "guest-1", 56));

        verify(mockUserRepo, times(1)).save(argThat(user -> "u1".equals(user.getUsername())));
    }

    // ----------------------------------------------------------------------
    // Reserved usernames (#76): identity.reserved-usernames plus the default admin's username,
    // matched ignoring case and surrounding whitespace.
    // ----------------------------------------------------------------------

    private static final IdentityProperties CUSTOM_RESERVED =
            new IdentityProperties(List.of("Box.Office", " support ", " "), "Root.Admin");

    @Test
    void GivenConfiguredReservedName_WhenIsUsernameReserved_ThenTrueWhateverItsCaseOrPadding() {
        AuthenticationService reserving = newService(fixedClock, new LockoutProperties(5, 15L), CUSTOM_RESERVED);

        assertTrue(reserving.isUsernameReserved("box.office"));
        assertTrue(reserving.isUsernameReserved("SUPPORT"));
        assertTrue(reserving.isUsernameReserved("  Support  "));
    }

    @Test
    void GivenPlatformAdminUsername_WhenIsUsernameReserved_ThenTrue() {
        AuthenticationService reserving = newService(fixedClock, new LockoutProperties(5, 15L), CUSTOM_RESERVED);

        assertTrue(reserving.isUsernameReserved("root.admin"));
    }

    @Test
    void GivenUnreservedOrNullOrBlankName_WhenIsUsernameReserved_ThenFalse() {
        AuthenticationService reserving = newService(fixedClock, new LockoutProperties(5, 15L), CUSTOM_RESERVED);

        assertFalse(reserving.isUsernameReserved("box.office.fan"));
        assertFalse(reserving.isUsernameReserved("admin"), "only the configured names are reserved");
        assertFalse(reserving.isUsernameReserved(null));
        assertFalse(reserving.isUsernameReserved(" "), "a blank configured entry reserves nothing");
    }

    @Test
    void GivenReservedUsername_WhenRegister_ThenReservedUsernameExceptionAndNothingSaved() {
        mockValidGuestSession("guest-1");

        assertThrows(ReservedUsernameException.class,
                () -> service.register(new RegisterRequestDTO(" Admin ", "a@example.com", "Password1", "guest-1", 56)));
        verifyNoInteractions(mockHasher);
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void givenBadEmailAndTakenUsername_whenRegister_thenFormatFailsFirst() {
        // Ordering check: format validators run before any repo lookup OR session
        // check.
        when(mockUserRepo.existsByUsername(any())).thenReturn(true);

        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", "not-an-email", "Password1", "guest-1", 56)));
        verify(mockUserRepo, never()).existsByUsername(any());
        verify(mockSessionRepo, never()).findById(any());
    }

    // ----------------------------------------------------------------------
    // Register — error messages never carry the email address (SLR.2).
    // LoggingAspect logs every application-service exception message at WARN,
    // so an address in the message would reach production logs. Usernames in
    // messages and log lines are open question OQ-26 in requirements.md.
    // ----------------------------------------------------------------------

    @Test
    void GivenTakenEmail_WhenRegister_ThenErrorMessageOmitsTheEmail() {
        String takenEmail = "dana.levi@example.com";
        String localPart = "dana.levi";
        mockValidGuestSession("guest-1");
        when(mockUserRepo.existsByUsername("alice")).thenReturn(false);
        when(mockUserRepo.findByEmail(takenEmail))
                .thenReturn(Optional.of(new User(1, "other", takenEmail, "HASH", 59)));

        DuplicateEmailException thrown = assertThrows(DuplicateEmailException.class,
                () -> service.register(new RegisterRequestDTO("alice", takenEmail, "Password1", "guest-1", 56)));

        String message = String.valueOf(thrown.getMessage());
        assertFalse(message.contains(localPart),
                "DuplicateEmailException message must not contain the email address or its local part, but was: "
                        + message);
    }

    @Test
    void GivenMalformedEmail_WhenRegister_ThenErrorMessageOmitsTheEmail() {
        String malformedEmail = "dana.levi@example";
        String localPart = "dana.levi";

        InvalidEmailFormatException thrown = assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", malformedEmail, "Password1", "guest-1", 56)));

        String message = String.valueOf(thrown.getMessage());
        assertFalse(message.contains(localPart),
                "InvalidEmailFormatException message must not contain the submitted address or its local part, but was: "
                        + message);
    }

    // ----------------------------------------------------------------------
    // Login — guest session required (D10a + spec II.1.5)
    // ----------------------------------------------------------------------

    @Test
    void givenNoGuestSessionId_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", null)));
        verifyNoInteractions(mockUserRepo);
        verifyNoInteractions(mockHasher);
    }

    @Test
    void givenUnknownGuestSessionId_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        when(mockSessionRepo.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", "ghost")));
    }

    @Test
    void givenMemberSessionIdAsGuest_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        Session memberSession = new Session("sid", 99, T0, T0.plusSeconds(3600));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(memberSession));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", "sid")));
    }

    @Test
    void givenExpiredGuestSession_whenLogin_thenGuestSessionRequiredExceptionThrown() {
        Session expired = new Session("sid", null, T0.minusSeconds(7200), T0.minusSeconds(60));
        when(mockSessionRepo.findById("sid")).thenReturn(Optional.of(expired));
        assertThrows(GuestSessionRequiredException.class,
                () -> service.login(new LoginRequestDTO("alice", "Password1", "sid")));
    }

    // ----------------------------------------------------------------------
    // Login — happy path + promotion + credential failures
    // ----------------------------------------------------------------------

    @Test
    void givenValidCredentials_whenLogin_thenTokenIssued() {
        mockValidGuestSession("guest-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("ISSUED_TOKEN");
        when(mockSessionManager.extractExpiration("ISSUED_TOKEN")).thenReturn(9999L);
        when(mockCartRestorationPort.restoreActiveOrder(7)).thenReturn(null);
        when(mockNotification.deliverPending(7)).thenReturn(null);

        LoginDTO loginResult = service.login(new LoginRequestDTO("alice", "Password1", "guest-1"));
        AuthTokenDTO result = loginResult.authToken();

        assertEquals("ISSUED_TOKEN", result.token());
        assertEquals(9999L, result.expiresAtEpochMillis());
        assertEquals(7, result.userId());
        assertEquals("alice", result.username());
    }

    @Test
    void givenValidCredentials_whenLogin_thenSessionPromotedInPlace() {
        Session guest = mockValidGuestSession("preserved-sid");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "preserved-sid"));

        // Same Session object — userId now set, sessionId preserved.
        assertTrue(guest.isMember());
        assertEquals(7, guest.getUserId());
        assertEquals("preserved-sid", guest.getSessionId());
        // Repo save was called on the now-promoted session.
        verify(mockSessionRepo, times(1)).save(guest);
        // JWT issued via generateTokenForSession (preserving the sid), not
        // generateToken.
        verify(mockSessionManager, times(1)).generateTokenForSession(guest, "alice");
        verify(mockSessionManager, never()).generateToken(anyInt(), any());
    }

    @Test
    void givenWrongPassword_whenLogin_thenAuthenticationFailedExceptionThrown() {
        mockValidGuestSession("guest-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 77);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("wrong", "STORED_HASH")).thenReturn(false);
        when(mockCartRestorationPort.restoreActiveOrder(7)).thenReturn(null);
        when(mockNotification.deliverPending(7)).thenReturn(null);

        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("alice", "wrong", "guest-1")));
        verify(mockSessionManager, never()).generateTokenForSession(any(), any());
    }

    @Test
    void givenNoSuchUser_whenLogin_thenSameGenericRejection() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.findByUsername("ghost")).thenReturn(Optional.empty());

        // Same exception type as wrong-password — no enumeration leak.
        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("ghost", "whatever", "guest-1")));
        verify(mockHasher, times(1)).matches(any(), any());
        verify(mockSessionManager, never()).generateTokenForSession(any(), any());
    }

    @Test
    void givenInvalidCredentials_whenLogin_thenRejected() {
        mockValidGuestSession("guest-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 89);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("badpass", "STORED_HASH")).thenReturn(false);

        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("alice", "badpass", "guest-1")));
    }

    // ----------------------------------------------------------------------
    // Delegates / logout — unchanged from Phase 2
    // ----------------------------------------------------------------------

    @Test
    void extractUserId_delegatesToSessionManager() {
        when(mockSessionManager.extractUserId("T")).thenReturn(42);
        assertEquals(42, service.extractUserId("T"));
        verify(mockSessionManager).extractUserId("T");
    }

    @Test
    void validateToken_delegatesToSessionManager() {
        when(mockSessionManager.validateToken("T")).thenReturn(true);
        assertTrue(service.validateToken("T"));
        verify(mockSessionManager).validateToken("T");
    }

    // liveMemberId answers "is this stored token still a member?" for the UI. An expired or revoked
    // token is an expected answer there, not a failure, so it comes back empty instead of throwing
    // (a throw would also be logged at WARN by LoggingAspect on every such check).
    @Test
    void GivenALiveToken_WhenLiveMemberIdIsAsked_ThenTheTokensUserId() {
        when(mockSessionManager.validateToken("T")).thenReturn(true);
        when(mockSessionManager.extractUserId("T")).thenReturn(42);

        assertEquals(OptionalInt.of(42), service.liveMemberId("T"));
    }

    @Test
    void GivenAnExpiredToken_WhenLiveMemberIdIsAsked_ThenEmptyWithoutThrowing() {
        when(mockSessionManager.validateToken("T")).thenThrow(new SessionExpiredException());

        OptionalInt memberId = assertDoesNotThrow(() -> service.liveMemberId("T"));

        assertEquals(OptionalInt.empty(), memberId, "an expired token names no live member");
    }

    @Test
    void GivenARevokedToken_WhenLiveMemberIdIsAsked_ThenEmptyWithoutThrowing() {
        when(mockSessionManager.validateToken("T")).thenThrow(new InvalidTokenException("revoked"));

        OptionalInt memberId = assertDoesNotThrow(() -> service.liveMemberId("T"));

        assertEquals(OptionalInt.empty(), memberId, "a revoked or invalid token names no live member");
    }

    // Admin and member ids share one id space, so an admin token must never read as the member with
    // the admin's id (#185). Runs on the real session manager: the answer depends on the token's kind.
    @Test
    void GivenAnAdminToken_WhenLiveMemberIdIsAsked_ThenEmpty() {
        int sharedId = 1;
        JwtSessionManager realSessionManager = new JwtSessionManager(new JwtProperties(JWT_TEST_SECRET),
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES),
                new MemorySessionRepository(fixedClock), fixedClock);
        AuthenticationService serviceOnRealSessions = new AuthenticationService(
                mockUserRepo, mockHasher, realSessionManager, mockCartRestorationPort, mockNotification,
                mockSessionRepo, mock(ISystemMetrics.class),
                fixedClock, mockAdminRepo,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), new LockoutProperties(5, 15L),
                IDENTITY, TestTransactions.noOpManager());
        String adminToken = realSessionManager.generateAdminToken(sharedId, "admin");

        assertEquals(OptionalInt.empty(), serviceOnRealSessions.liveMemberId(adminToken),
                "an admin token names no member, even when a member has the admin's id");
    }

    @Test
    void GivenAnUnexpectedValidationFailure_WhenLiveMemberIdIsAsked_ThenItPropagates() {
        when(mockSessionManager.validateToken("T")).thenThrow(new IllegalStateException("session store down"));

        assertThrows(IllegalStateException.class, () -> service.liveMemberId("T"),
                "only an expired or rejected token means \"no member\"; other failures must surface");
    }

    @Test
    void logout_delegatesToSessionManagerInvalidate() {
        service.logout(new LogoutRequestDTO("SOME_TOKEN"));
        verify(mockSessionManager).invalidate("SOME_TOKEN");
    }

    // #187: a read of the session store before the revocation adds a way for logout to fail, and one
    // that a failure in it would roll the revocation back with, since both share logout's transaction.
    @Test
    void GivenAToken_WhenLogout_ThenItIsRevokedWithoutReadingTheSessionStoreFirst() {
        service.logout(new LogoutRequestDTO("SOME_TOKEN"));

        verify(mockSessionManager).invalidate("SOME_TOKEN");
        verify(mockSessionManager, never()).tryExtractUserId(any());
    }

    // #187: a logout whose revocation failed leaves the session, and so the token, live; it must not
    // look like it succeeded.
    @Test
    void GivenTheSessionStoreFails_WhenLogout_ThenTheFailurePropagates() {
        DataAccessResourceFailureException storeFailure =
                new DataAccessResourceFailureException("session store unreachable");
        doThrow(storeFailure).when(mockSessionManager).invalidate("SOME_TOKEN");

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> service.logout(new LogoutRequestDTO("SOME_TOKEN")));

        assertSame(storeFailure, thrown, "logout must let the session store's own failure out unchanged");
    }

    @Test
    void logout_idempotent_doesNotThrowForGarbageToken() {
        service.logout(new LogoutRequestDTO("not-a-real-token"));
        verify(mockSessionManager).invalidate("not-a-real-token");
    }

    // ----------------------------------------------------------------------
    // D9a — cart handling on login promotion
    //
    // The concrete guest-cart-merge / orphaned-member-cart-restore behavior now
    // lives in the sales-owned CartRestorationAdapter (see CartRestorationAdapterTest).
    // AuthenticationService's contract is only that login delegates to the port,
    // inside the login transaction, with the promoted session id and user id.
    // ----------------------------------------------------------------------

    @Test
    void login_delegatesCartMergeToPortOnPromotion() {
        mockValidGuestSession("preserved-sid");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");

        service.login(new LoginRequestDTO("alice", "Password1", "preserved-sid"));

        // Login hands the D9a merge off to the sales adapter with the promoted ids.
        verify(mockCartRestorationPort, times(1)).mergeGuestCartOnPromotion("preserved-sid", 7);
    }

    @Test
    void login_returnsRestoredCartFromPort() {
        mockValidGuestSession("sid-1");
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");
        // The cart the sales adapter restores is carried through into the LoginDTO.
        com.ticketing.system.shared.dto.ActiveOrderDTO restored =
                new com.ticketing.system.shared.dto.ActiveOrderDTO(7, null, null, 0L, 0.0, java.util.List.of());
        when(mockCartRestorationPort.restoreActiveOrder(7)).thenReturn(restored);

        LoginDTO loginResult = service.login(new LoginRequestDTO("alice", "Password1", "sid-1"));

        // Restored cart flows through unchanged (behavior preserved by the port).
        assertEquals(restored, loginResult.activeOrder());
        verify(mockCartRestorationPort, times(1)).restoreActiveOrder(7);
    }

    @Test
    void logout_doesNotTouchCart() {
        // D9a: Member cart must survive logout, restored on next login — logout must
        // never invoke the cart-restoration port at all.
        service.logout(new LogoutRequestDTO("SOME_TOKEN"));
        verifyNoInteractions(mockCartRestorationPort);
    }

    // ---- admin sign-in (#290) ----

    @Test
    void signInAsAdmin_validCredentials_returnsAdminFlaggedToken() {
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));
        when(mockHasher.matches("pw", "hashed")).thenReturn(true);
        when(mockSessionManager.generateAdminToken(1, "admin")).thenReturn("admin-jwt");
        when(mockSessionManager.extractExpiration("admin-jwt")).thenReturn(999L);

        AuthTokenDTO token = service.signInAsAdmin("admin", "pw");

        assertTrue(token.isAdmin());
        assertEquals("admin", token.username());
        assertEquals("admin-jwt", token.token());
    }

    @Test
    void signInAsAdmin_unknownUsername_throwsAuthenticationFailed() {
        when(mockAdminRepo.findByUsername("ghost")).thenReturn(null);
        assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("ghost", "pw"));
    }

    @Test
    void signInAsAdmin_wrongPassword_throwsAuthenticationFailed() {
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));
        when(mockHasher.matches("wrong", "hashed")).thenReturn(false);
        assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("admin", "wrong"));
    }

    @Test
    void signInAsAdmin_locksAfterFiveFailures() {
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));
        when(mockHasher.matches(any(), any())).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("admin", "wrong"));
        }
        // 6th attempt is blocked even with the correct password.
        when(mockHasher.matches("pw", "hashed")).thenReturn(true);
        assertThrows(AccountLockedException.class, () -> service.signInAsAdmin("admin", "pw"));
    }

    // ----------------------------------------------------------------------
    // Session ids never reach the log (#185). A guest's raw session id is the guest's credential
    // (Session), and login keeps the same id for the member session, so no line may carry it at any
    // level. A non-reversible reference is fine.
    // ----------------------------------------------------------------------

    private static final String SESSION_ID = "4b1f9c2e-7a35-4d8e-9f60-session-under-test";

    private LogCapture logs;

    @AfterEach
    void detachLogCapture() {
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void GivenNoSession_WhenStartGuestSession_ThenNoLogEventCarriesTheSessionId() {
        logs = LogCapture.of(AuthenticationService.class);

        String sessionId = service.startGuestSession().sessionId();

        assertTrue(logs.eventCount() > 0, "control: starting a guest session logs, so the capture sees its logger");
        assertEquals(List.of(), logs.eventsContaining(sessionId),
                "the new guest session id is the guest's credential and must not be logged");
    }

    @Test
    void GivenGuestSession_WhenEndGuestSession_ThenNoLogEventCarriesTheSessionId() {
        mockValidGuestSession(SESSION_ID);
        logs = LogCapture.of(AuthenticationService.class);

        service.endGuestSession(SESSION_ID);

        assertTrue(logs.eventCount() > 0, "control: ending a guest session logs, so the capture sees its logger");
        assertEquals(List.of(), logs.eventsContaining(SESSION_ID),
                "the ended guest session id must not be logged");
    }

    @Test
    void GivenValidCredentials_WhenLogin_ThenNoLogEventCarriesTheSessionId() {
        mockValidGuestSession(SESSION_ID);
        User user = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(user));
        when(mockHasher.matches("Password1", "STORED_HASH")).thenReturn(true);
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");
        logs = LogCapture.of(AuthenticationService.class);

        service.login(new LoginRequestDTO("alice", "Password1", SESSION_ID));

        assertTrue(logs.eventCount() > 0, "control: a login logs, so the capture sees its logger");
        assertEquals(List.of(), logs.eventsContaining(SESSION_ID),
                "login keeps the guest's session id for the member session, so it must not be logged");
    }

    // ----------------------------------------------------------------------
    // Register — the email check is bounded (#185). RFC 5321 caps a forward path at 254 characters,
    // so anything longer is rejected before the format check, whose backtracking grows with the input.
    // ----------------------------------------------------------------------

    private static final int MAX_EMAIL_LENGTH = 254;
    // A length check rejects the input in microseconds; the format check on it took about 3 s when
    // measured. 500 ms leaves a slow CI runner over a hundred times what the bounded check needs.
    private static final Duration BOUNDED_EMAIL_CHECK = Duration.ofMillis(500);

    @Test
    void GivenEmailWith20000DotsBetweenAts_WhenRegister_ThenInvalidEmailFormat() {
        String email = "a@" + ".".repeat(20_000) + "@";
        RegisterRequestDTO request = new RegisterRequestDTO("alice", email, "Password1", "guest-1", 56);

        assertTimeoutPreemptively(BOUNDED_EMAIL_CHECK,
                () -> assertThrows(InvalidEmailFormatException.class, () -> service.register(request)),
                "an over-long email must be rejected by its length, before the format check runs");
    }

    @Test
    void GivenWellFormedEmailOf255Characters_WhenRegister_ThenInvalidEmailFormat() {
        String email = wellFormedEmailOfLength(MAX_EMAIL_LENGTH + 1);

        assertThrows(InvalidEmailFormatException.class,
                () -> service.register(new RegisterRequestDTO("alice", email, "Password1", "guest-1", 56)),
                "an email longer than " + MAX_EMAIL_LENGTH + " characters must be rejected");
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void GivenWellFormedEmailOf254Characters_WhenRegister_ThenRegistered() {
        String email = wellFormedEmailOfLength(MAX_EMAIL_LENGTH);
        givenRegistrationWillSucceed("guest-1");

        assertDoesNotThrow(
                () -> service.register(new RegisterRequestDTO("alice", email, "Password1", "guest-1", 56)),
                "an email of exactly " + MAX_EMAIL_LENGTH + " characters is within the limit");
        verify(mockUserRepo, times(1)).save(any(User.class));
    }

    /** A {@code local@domain.com} address of exactly {@code length} characters. */
    private static String wellFormedEmailOfLength(int length) {
        String local = "a".repeat(64);
        String tld = ".com";
        String domain = "b".repeat(length - local.length() - 1 - tld.length());
        String email = local + "@" + domain + tld;
        assertEquals(length, email.length(), "test fixture: email length");
        return email;
    }

    private void givenRegistrationWillSucceed(String guestSessionId) {
        mockValidGuestSession(guestSessionId);
        when(mockUserRepo.existsByUsername(any())).thenReturn(false);
        when(mockUserRepo.findByEmail(any())).thenReturn(Optional.empty());
        when(mockUserRepo.nextId()).thenReturn(42);
        when(mockHasher.hash(any())).thenReturn("HASHED");
    }

    // ----------------------------------------------------------------------
    // Register — BCrypt reads at most 72 bytes of a password and the hasher throws past that, so
    // register rejects a password whose UTF-8 encoding is longer, as a weak password (#185). The
    // limit is in bytes, not characters: a Hebrew letter takes two.
    // ----------------------------------------------------------------------

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;
    private static final String HEBREW_ALEF = "א";

    @Test
    void GivenAsciiPasswordOf73Bytes_WhenRegister_ThenWeakPassword() {
        String password = passwordOfUtf8Bytes("a".repeat(72) + "1", BCRYPT_MAX_PASSWORD_BYTES + 1);
        givenRegistrationWillSucceed("guest-1");

        assertThrows(WeakPasswordException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", password, "guest-1", 56)));
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void GivenHebrewPasswordOf37CharactersAnd73Bytes_WhenRegister_ThenWeakPassword() {
        String password = passwordOfUtf8Bytes(HEBREW_ALEF.repeat(36) + "1", BCRYPT_MAX_PASSWORD_BYTES + 1);
        givenRegistrationWillSucceed("guest-1");

        assertThrows(WeakPasswordException.class,
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", password, "guest-1", 56)),
                "the limit is 72 UTF-8 bytes, so 37 characters that are mostly two-byte are already too many");
        verify(mockUserRepo, never()).save(any(User.class));
    }

    @Test
    void GivenAsciiPasswordOf72Bytes_WhenRegister_ThenRegistered() {
        String password = passwordOfUtf8Bytes("a".repeat(71) + "1", BCRYPT_MAX_PASSWORD_BYTES);
        givenRegistrationWillSucceed("guest-1");

        assertDoesNotThrow(
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", password, "guest-1", 56)));
        verify(mockHasher, times(1)).hash(password);
    }

    @Test
    void GivenHebrewPasswordOf72Bytes_WhenRegister_ThenRegistered() {
        // 35 two-byte letters and two digits. The password rules need a digit, and no one-byte
        // character fits beside 36 Hebrew letters in 72 bytes.
        String password = passwordOfUtf8Bytes(HEBREW_ALEF.repeat(35) + "12", BCRYPT_MAX_PASSWORD_BYTES);
        givenRegistrationWillSucceed("guest-1");

        assertDoesNotThrow(
                () -> service.register(new RegisterRequestDTO("alice", "alice@example.com", password, "guest-1", 56)));
        verify(mockHasher, times(1)).hash(password);
    }

    private static String passwordOfUtf8Bytes(String password, int expectedBytes) {
        assertEquals(expectedBytes, password.getBytes(StandardCharsets.UTF_8).length, "test fixture: UTF-8 length");
        return password;
    }

    // ----------------------------------------------------------------------
    // Login — an unknown username costs the same password check as a known one (#185), so the
    // response time does not tell which usernames exist.
    // ----------------------------------------------------------------------

    @Test
    void GivenUnknownUsername_WhenLogin_ThenPasswordHasherRunsOnce() {
        mockValidGuestSession("guest-1");
        when(mockUserRepo.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(AuthenticationFailedException.class,
                () -> service.login(new LoginRequestDTO("ghost", "whatever", "guest-1")));

        verify(mockHasher, times(1)).matches(eq("whatever"), argThat(hash -> hash != null && !hash.isBlank()));
    }

    @Test
    void GivenUnknownAdminUsername_WhenSignInAsAdmin_ThenPasswordHasherRunsOnce() {
        when(mockAdminRepo.findByUsername("ghost")).thenReturn(null);

        assertThrows(AuthenticationFailedException.class, () -> service.signInAsAdmin("ghost", "whatever"));

        verify(mockHasher, times(1)).matches(eq("whatever"), argThat(hash -> hash != null && !hash.isBlank()));
    }

    // The hash an unknown username is checked against comes from the injected hasher, so it always
    // costs what a real member's hash costs, whatever the hasher's configuration (#185).

    private static final String BCRYPT_COST_10_HASH = "^\\$2[aby]\\$10\\$.{53}$";

    @Test
    void GivenRealBcryptHasher_WhenUnknownUsernameLogsIn_ThenTheCheckedHashIsACost10HashTheHasherMade() {
        BcryptPasswordHasher realHasher = spy(new BcryptPasswordHasher(new BCryptPasswordEncoder()));
        List<String> hashesMade = recordHashesMadeBy(realHasher);
        AuthenticationService onRealHasher = serviceWithHasher(realHasher);
        mockValidGuestSession("guest-1");
        when(mockUserRepo.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(AuthenticationFailedException.class,
                () -> onRealHasher.login(new LoginRequestDTO("ghost", "whatever", "guest-1")));

        ArgumentCaptor<String> checkedHash = ArgumentCaptor.forClass(String.class);
        verify(realHasher, times(1)).matches(eq("whatever"), checkedHash.capture());
        assertTrue(checkedHash.getValue().matches(BCRYPT_COST_10_HASH),
                "the dummy hash must be a BCrypt hash at cost 10, but was " + checkedHash.getValue());
        assertEquals(List.of(checkedHash.getValue()), hashesMade,
                "the dummy hash must be the one the injected hasher made");
    }

    @Test
    void GivenServiceBuiltOnce_WhenTwoUnknownUsernamesLogIn_ThenTheHasherMadeOneDummyHashAtConstruction() {
        BcryptPasswordHasher realHasher = spy(new BcryptPasswordHasher(new BCryptPasswordEncoder()));
        List<String> hashesMade = recordHashesMadeBy(realHasher);
        AuthenticationService onRealHasher = serviceWithHasher(realHasher);
        assertEquals(1, hashesMade.size(), "the dummy hash is made once, when the service is built");
        mockValidGuestSession("guest-1");
        when(mockUserRepo.findByUsername(any())).thenReturn(Optional.empty());

        assertThrows(AuthenticationFailedException.class,
                () -> onRealHasher.login(new LoginRequestDTO("ghost-1", "whatever", "guest-1")));
        assertThrows(AuthenticationFailedException.class,
                () -> onRealHasher.login(new LoginRequestDTO("ghost-2", "whatever", "guest-1")));

        assertEquals(1, hashesMade.size(), "an unknown username must not cost a hash of its own");
        verify(realHasher, times(2)).matches(eq("whatever"), eq(hashesMade.get(0)));
    }

    private static List<String> recordHashesMadeBy(PasswordHasher hasher) {
        List<String> made = new ArrayList<>();
        doAnswer(invocation -> {
            String hash = (String) invocation.callRealMethod();
            made.add(hash);
            return hash;
        }).when(hasher).hash(any());
        return made;
    }

    private AuthenticationService serviceWithHasher(PasswordHasher hasher) {
        return new AuthenticationService(
                mockUserRepo, hasher, mockSessionManager, mockCartRestorationPort, mockNotification,
                mockSessionRepo, mock(ISystemMetrics.class),
                fixedClock, mockAdminRepo,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), new LockoutProperties(5, 15L),
                IDENTITY, TestTransactions.noOpManager());
    }

    // ----------------------------------------------------------------------
    // Lockout (SLR.2, #185) — failures are counted in a window that starts at a key's first failure
    // and lasts auth.lockout.lock-minutes. Once the window passes, the count starts from zero, so a
    // key that never reached the limit does not stay in memory forever.
    // ----------------------------------------------------------------------

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 15;
    private static final String RIGHT_PASSWORD = "Password1";
    private static final String WRONG_PASSWORD = "wrong-password";

    @Test
    void GivenFewerThanMaxFailures_WhenTheLockWindowHasPassed_ThenTheCountStartsFromZero() {
        AdjustableClock clock = new AdjustableClock(T0);
        AuthenticationService lockoutService = newService(clock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(clock);

        failLogins(lockoutService, MAX_ATTEMPTS - 1);
        clock.advance(Duration.ofMinutes(LOCK_MINUTES + 1));
        failLogins(lockoutService, MAX_ATTEMPTS - 1);

        assertDoesNotThrow(() -> lockoutService.login(new LoginRequestDTO("alice", RIGHT_PASSWORD, "guest-1")),
                "failures from a window that has passed must not count towards the lock");
    }

    @Test
    void GivenFailuresSpreadOverTheWindow_WhenTheWindowSinceTheFirstFailurePasses_ThenTheCountStartsFromZero() {
        AdjustableClock clock = new AdjustableClock(T0);
        AuthenticationService lockoutService = newService(clock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(clock);

        failLogins(lockoutService, 1);
        clock.advance(Duration.ofMinutes(LOCK_MINUTES - 5));
        failLogins(lockoutService, MAX_ATTEMPTS - 2);
        clock.advance(Duration.ofMinutes(6));
        failLogins(lockoutService, 1);

        assertDoesNotThrow(() -> lockoutService.login(new LoginRequestDTO("alice", RIGHT_PASSWORD, "guest-1")),
                "the window runs from the first failure, so the failure after it opens a new count");
    }

    @Test
    void GivenMaxMinusOneFailures_WhenOneMoreFailsInsideTheWindow_ThenLocked() {
        AdjustableClock clock = new AdjustableClock(T0);
        AuthenticationService lockoutService = newService(clock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(clock);

        failLogins(lockoutService, MAX_ATTEMPTS - 1);
        clock.advance(Duration.ofMinutes(LOCK_MINUTES - 1));
        failLogins(lockoutService, 1);

        assertThrows(AccountLockedException.class,
                () -> lockoutService.login(new LoginRequestDTO("alice", RIGHT_PASSWORD, "guest-1")),
                MAX_ATTEMPTS + " failures inside one window must lock the account");
    }

    // The locked message rounds the time left in the window up to whole minutes.
    @Test
    void GivenLockWithNinetySecondsLeft_WhenLogin_ThenTheMessageSaysAboutTwoMinutes() {
        AdjustableClock clock = new AdjustableClock(T0);
        AuthenticationService lockoutService = newService(clock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(clock);
        failLogins(lockoutService, MAX_ATTEMPTS);
        clock.advance(Duration.ofMinutes(LOCK_MINUTES).minusSeconds(90));

        AccountLockedException locked = assertThrows(AccountLockedException.class,
                () -> lockoutService.login(new LoginRequestDTO("alice", RIGHT_PASSWORD, "guest-1")));

        assertEquals("Too many failed attempts. Try again in about 2 minutes.", locked.getMessage());
    }

    // With the limit at N, every attempt beyond the N-th inside the window is refused before its
    // password is checked, even when the attempts arrive together.

    private static final int CONCURRENT_MAX_ATTEMPTS = 3;
    private static final int ATTEMPTS_OVER_THE_LIMIT = 4;
    private static final long CONCURRENCY_AWAIT_SECONDS = 5;

    @Test
    void GivenMaxAttemptsN_WhenMoreThanNWrongMemberPasswordsArriveTogether_ThenExactlyNPasswordVerifications()
            throws InterruptedException {
        AuthenticationService lockoutService = newService(fixedClock,
                new LockoutProperties(CONCURRENT_MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(fixedClock);

        int verifications = passwordVerificationsUnderSimultaneousAttempts(
                CONCURRENT_MAX_ATTEMPTS + ATTEMPTS_OVER_THE_LIMIT,
                () -> lockoutService.login(new LoginRequestDTO("alice", WRONG_PASSWORD, "guest-1")));

        assertEquals(CONCURRENT_MAX_ATTEMPTS, verifications,
                "expected exactly " + CONCURRENT_MAX_ATTEMPTS + " password verifications for "
                        + (CONCURRENT_MAX_ATTEMPTS + ATTEMPTS_OVER_THE_LIMIT)
                        + " simultaneous attempts, but there were " + verifications);
    }

    @Test
    void GivenMaxAttemptsN_WhenMoreThanNWrongAdminPasswordsArriveTogether_ThenExactlyNPasswordVerifications()
            throws InterruptedException {
        AuthenticationService lockoutService = newService(fixedClock,
                new LockoutProperties(CONCURRENT_MAX_ATTEMPTS, LOCK_MINUTES));
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "hashed", true));

        int verifications = passwordVerificationsUnderSimultaneousAttempts(
                CONCURRENT_MAX_ATTEMPTS + ATTEMPTS_OVER_THE_LIMIT,
                () -> lockoutService.signInAsAdmin("admin", WRONG_PASSWORD));

        assertEquals(CONCURRENT_MAX_ATTEMPTS, verifications,
                "expected exactly " + CONCURRENT_MAX_ATTEMPTS + " password verifications for "
                        + (CONCURRENT_MAX_ATTEMPTS + ATTEMPTS_OVER_THE_LIMIT)
                        + " simultaneous attempts, but there were " + verifications);
    }

    /**
     * Runs {@code attempts} wrong-password sign-ins at once and counts the password checks. Each
     * check is held open until every attempt has either reached its own check or finished, so no
     * attempt records its failure before the others have passed the lock check: every attempt let
     * through to the hasher is counted, however the threads are scheduled. If an implementation
     * makes attempts wait for each other, the hold gives up after a bounded wait.
     */
    private int passwordVerificationsUnderSimultaneousAttempts(int attempts, Runnable attempt)
            throws InterruptedException {
        AtomicInteger verifications = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(attempts);
        CountDownLatch release = new CountDownLatch(1);
        ThreadLocal<Boolean> reachedHasher = ThreadLocal.withInitial(() -> false);
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        when(mockHasher.matches(any(), any())).thenAnswer(invocation -> {
            verifications.incrementAndGet();
            reachedHasher.set(true);
            settled.countDown();
            release.await(CONCURRENCY_AWAIT_SECONDS, TimeUnit.SECONDS);
            return false;
        });

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        attempt.run();
                    } catch (AuthenticationFailedException | AccountLockedException expected) {
                        // A refused sign-in is the expected outcome of every attempt.
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        if (!reachedHasher.get()) {
                            settled.countDown();
                        }
                    }
                });
            }
            start.countDown();
            settled.await(CONCURRENCY_AWAIT_SECONDS, TimeUnit.SECONDS);
            release.countDown();
        } finally {
            pool.shutdown();
        }
        assertTrue(pool.awaitTermination(CONCURRENCY_AWAIT_SECONDS * 2, TimeUnit.SECONDS),
                "the sign-in attempts did not finish");
        assertEquals(List.of(), List.copyOf(unexpected), "an attempt failed with something other than a refusal");
        return verifications.get();
    }

    private AuthenticationService newService(Clock clock, LockoutProperties lockout) {
        return newService(clock, lockout, IDENTITY);
    }

    private AuthenticationService newService(Clock clock, LockoutProperties lockout, IdentityProperties identity) {
        AuthenticationService built = new AuthenticationService(
                mockUserRepo, mockHasher, mockSessionManager, mockCartRestorationPort, mockNotification,
                mockSessionRepo, mock(ISystemMetrics.class),
                clock, mockAdminRepo,
                new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES), lockout, identity,
                TestTransactions.noOpManager());
        clearInvocations(mockHasher);
        return built;
    }

    /** Member alice, whose password is {@link #RIGHT_PASSWORD}; every session id names a fresh guest. */
    private void givenMemberAliceWithAnyGuestSession(Clock clock) {
        User alice = new User(7, "alice", "alice@example.com", "STORED_HASH", 56);
        when(mockUserRepo.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(mockHasher.matches(any(), eq("STORED_HASH")))
                .thenAnswer(invocation -> RIGHT_PASSWORD.equals(invocation.getArgument(0)));
        when(mockSessionRepo.findById(any())).thenAnswer(invocation -> {
            Instant now = clock.instant();
            return Optional.of(new Session(invocation.getArgument(0), null, now,
                    now.plus(GUEST_IDLE_MINUTES, ChronoUnit.MINUTES)));
        });
        when(mockSessionManager.generateTokenForSession(any(), any())).thenReturn("TOK");
    }

    private static void failLogins(AuthenticationService target, int times) {
        failLoginsAs(target, "alice", times);
    }

    private static void failLoginsAs(AuthenticationService target, String username, int times) {
        for (int i = 0; i < times; i++) {
            assertThrows(AuthenticationFailedException.class,
                    () -> target.login(new LoginRequestDTO(username, WRONG_PASSWORD, "guest-1")),
                    "a wrong password inside an unlocked window is refused as a failed sign-in, not a lock");
        }
    }

    // ----------------------------------------------------------------------
    // Lockout key (SLR.2, OQ-8, #187). A member's attempts are counted under the username exactly as
    // member accounts are looked up: names that differ only in case are distinct accounts (business
    // rule 1), and a padded name names no account. So a sign-in to one account never clears another
    // account's window, and failures at one never lock another. Admins are looked up by the trimmed
    // name, so an admin's window is keyed by the trimmed name.
    // ----------------------------------------------------------------------

    private static final String LOOK_ALIKE_PASSWORD = "Lookalike1";

    @Test
    void GivenMaxMinusOneFailuresAtAlice_WhenALICESignsInAndAliceFailsTwiceMore_ThenTheSecondIsLocked() {
        AuthenticationService lockoutService = newService(fixedClock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(fixedClock);
        givenLookAlikeMemberALICE();

        failLogins(lockoutService, MAX_ATTEMPTS - 1);
        lockoutService.login(new LoginRequestDTO("ALICE", LOOK_ALIKE_PASSWORD, "guest-1"));
        failLogins(lockoutService, 1);

        assertThrows(AccountLockedException.class,
                () -> lockoutService.login(new LoginRequestDTO("alice", WRONG_PASSWORD, "guest-1")),
                "a sign-in to the distinct account ALICE must not clear alice's attempt window");
    }

    @Test
    void GivenMaxFailuresAtALICE_WhenAliceSignsInWithHerPassword_ThenSignedIn() {
        AuthenticationService lockoutService = newService(fixedClock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(fixedClock);
        givenLookAlikeMemberALICE();

        failLoginsAs(lockoutService, "ALICE", MAX_ATTEMPTS);

        assertDoesNotThrow(() -> lockoutService.login(new LoginRequestDTO("alice", RIGHT_PASSWORD, "guest-1")),
                "failures at the distinct account ALICE must not lock alice");
    }

    @Test
    void GivenMaxFailuresAtAliceWithALeadingSpace_WhenAliceSignsInWithHerPassword_ThenSignedIn() {
        AuthenticationService lockoutService = newService(fixedClock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        givenMemberAliceWithAnyGuestSession(fixedClock);

        failLoginsAs(lockoutService, " alice", MAX_ATTEMPTS);

        assertDoesNotThrow(() -> lockoutService.login(new LoginRequestDTO("alice", RIGHT_PASSWORD, "guest-1")),
                "' alice' names no member account, so failures at it must not lock alice");
    }

    @Test
    void GivenFailuresAtPaddedAdminAndAtAdmin_WhenTheyReachMaxAttempts_ThenAdminIsLocked() {
        AuthenticationService lockoutService = newService(fixedClock, new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES));
        String adminPassword = "AdminPass1";
        when(mockAdminRepo.findByUsername("admin")).thenReturn(new Admin(1, "admin", "ADMIN_HASH", true));
        when(mockHasher.matches(any(), eq("ADMIN_HASH")))
                .thenAnswer(invocation -> adminPassword.equals(invocation.getArgument(0)));

        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            assertThrows(AuthenticationFailedException.class,
                    () -> lockoutService.signInAsAdmin(" admin", WRONG_PASSWORD));
        }
        assertThrows(AuthenticationFailedException.class, () -> lockoutService.signInAsAdmin("admin", WRONG_PASSWORD));

        assertThrows(AccountLockedException.class, () -> lockoutService.signInAsAdmin("admin", adminPassword),
                "' admin' signs in to the admin account, so its failures count in admin's window");
    }

    /** Member ALICE, a distinct account from alice, whose password is {@link #LOOK_ALIKE_PASSWORD}. */
    private void givenLookAlikeMemberALICE() {
        User lookAlike = new User(8, "ALICE", "look.alike@example.com", "LOOK_ALIKE_HASH", 30);
        when(mockUserRepo.findByUsername("ALICE")).thenReturn(Optional.of(lookAlike));
        when(mockHasher.matches(any(), eq("LOOK_ALIKE_HASH")))
                .thenAnswer(invocation -> LOOK_ALIKE_PASSWORD.equals(invocation.getArgument(0)));
    }
}
