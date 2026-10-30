package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.adapter.out.persistence.MemorySessionRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.identity.config.JwtProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * Member data needs a credential (#187), as every application service call does: the member search
 * an admin token (the admin readers, ADR-012), and a member's profile the member's own token, from
 * which the member is found, so no caller names another member's id. Runs on the real session
 * manager, since the answer depends on the token's kind: member and admin ids share one id space,
 * so an admin token must not read as the member with the admin's id.
 */
class MemberQueryServiceCredentialTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    // At least 256 bits, as JwtSessionManager requires of its HMAC secret.
    private static final String JWT_TEST_SECRET =
            "unit-test-secret-must-be-long-enough-for-hmac-sha-256-please-ignore-me";
    // The same integer on purpose: member and admin ids share one id space.
    private static final int MEMBER_ID = 7;
    private static final int ADMIN_ID = 7;

    private UserRepository userRepository;
    private JwtSessionManager sessionManager;
    private MemberQueryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        userRepository = mock(UserRepository.class);
        sessionManager = new JwtSessionManager(new JwtProperties(JWT_TEST_SECRET), new SessionProperties(1440, 30),
                new MemorySessionRepository(clock), clock);
        service = new MemberQueryService(userRepository, sessionManager);
    }

    // ---- getMemberProfile(memberToken) ----

    @Test
    void GivenMemberToken_WhenGetMemberProfile_ThenThatMembersProfile() {
        String memberToken = sessionManager.generateToken(MEMBER_ID, "alice");
        when(userRepository.getUserById(MEMBER_ID))
                .thenReturn(new User(MEMBER_ID, "alice", "alice@example.com", "HASH", 30));

        MemberDTO profile = service.getMemberProfile(memberToken);

        assertEquals(new MemberDTO(MEMBER_ID, "alice", "alice@example.com"), profile);
    }

    @Test
    void GivenAdminTokenWithAMembersId_WhenGetMemberProfile_ThenRefusedWithoutReadingAnyMember() {
        String adminToken = sessionManager.generateAdminToken(ADMIN_ID, "admin");

        assertThrows(InvalidTokenException.class, () -> service.getMemberProfile(adminToken),
                "an admin token names no member, even when a member has the admin's id");
        verifyNoInteractions(userRepository);
    }

    @Test
    void GivenMalformedToken_WhenGetMemberProfile_ThenRefusedWithoutReadingAnyMember() {
        assertThrows(InvalidTokenException.class, () -> service.getMemberProfile("not.a.jwt"));
        verifyNoInteractions(userRepository);
    }

    // ---- searchByUsername(adminToken, query) ----

    @Test
    void GivenAdminToken_WhenSearchByUsername_ThenTheMatchingMembersSortedByUsername() {
        String adminToken = sessionManager.generateAdminToken(ADMIN_ID, "admin");
        when(userRepository.findAll()).thenReturn(List.of(
                new User(2, "Malice", "malice@example.com", "HASH", 30),
                new User(1, "alice", "alice@example.com", "HASH", 30),
                new User(3, "bob", "bob@example.com", "HASH", 30)));

        List<MemberSearchResultDTO> found = service.searchByUsername(adminToken, "ali");

        assertEquals(List.of(new MemberSearchResultDTO(1, "alice"), new MemberSearchResultDTO(2, "Malice")), found);
    }

    @Test
    void GivenMemberToken_WhenSearchByUsername_ThenRefusedWithoutReadingAnyMember() {
        String memberToken = sessionManager.generateToken(MEMBER_ID, "alice");

        assertThrows(InvalidTokenException.class, () -> service.searchByUsername(memberToken, "ali"),
                "the member search is an admin capability (II.6.3.2)");
        verifyNoInteractions(userRepository);
    }

    @Test
    void GivenMalformedToken_WhenSearchByUsername_ThenRefusedWithoutReadingAnyMember() {
        assertThrows(InvalidTokenException.class, () -> service.searchByUsername("not.a.jwt", "ali"));
        verifyNoInteractions(userRepository);
    }

    @Test
    void GivenNoToken_WhenSearchByUsername_ThenRefusedWithoutReadingAnyMember() {
        assertThrows(DomainException.class, () -> service.searchByUsername(null, "ali"),
                "a missing token must be refused like an invalid one");
        verifyNoInteractions(userRepository);
    }
}
