package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.messaging.adapter.out.persistence.MemoryConversationRepository;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.shared.dto.SubmitComplaintRequestDTO;

// ---- A whitespace or null complaint body is accepted (#187) ----
//
// submitComplaint folds a related-entity reference into the stored body. The details the member
// typed must be checked before that, or a blank body passes as "   \n\n[Related: ...]" and a null
// one is stored as "null\n\n[Related: ...]".
class MessagingServiceComplaintBodyTest {

    private static final String MEMBER_TOKEN = "member-tok";
    private static final int MEMBER_ID = 10;
    private static final int MEMBER_AGE = 30;
    private static final Instant NOW = Instant.parse("2027-07-15T17:00:00Z");
    private static final String RELATED_REF = "EVENT:123";
    private static final String NO_REF = null;

    private ConversationRepository conversations;
    private MessagingService service;

    @BeforeEach
    void setUp() {
        conversations = new MemoryConversationRepository();
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(sessions.extractUserId(MEMBER_TOKEN)).thenReturn(MEMBER_ID);
        MemoryUserRepository users = new MemoryUserRepository();
        users.save(new User(MEMBER_ID, "member", "member@example.test", "unused-password-hash", MEMBER_AGE));
        service = new MessagingService(conversations, sessions, mock(AdminRepository.class), users,
                new MemoryProductionCompanyRepository(), mock(CompanyMembershipService.class),
                mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Stream<Arguments> blankBodiesWithAndWithoutAReference() {
        return Stream.of(
                Arguments.of(null, NO_REF),
                Arguments.of("", NO_REF),
                Arguments.of("   ", NO_REF),
                Arguments.of("\t\n", NO_REF),
                Arguments.of(null, RELATED_REF),
                Arguments.of("", RELATED_REF),
                Arguments.of("   ", RELATED_REF),
                Arguments.of("\t\n", RELATED_REF));
    }

    @ParameterizedTest(name = "[{index}] body=<{0}> related={1}")
    @MethodSource("blankBodiesWithAndWithoutAReference")
    void GivenABlankOrNullBody_WhenSubmitComplaint_ThenRefusedWithIllegalArgumentAndNothingIsStored(
            String body, String relatedEntityRef) {
        SubmitComplaintRequestDTO request = new SubmitComplaintRequestDTO(MEMBER_ID, "Refund", body, relatedEntityRef);

        assertThrows(IllegalArgumentException.class, () -> service.submitComplaint(MEMBER_TOKEN, request));

        assertTrue(conversations.findMemberInbox(MEMBER_ID).isEmpty(),
                () -> "no complaint is stored, found: " + conversations.findMemberInbox(MEMBER_ID).size());
    }
}
