package com.ticketing.system.identity.application.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only queries on the User aggregate that the presentation layer
 * needs but {@code AuthenticationService} (write-side) doesn't expose.
 *
 * <p>The live uniqueness check on {@code RegisterView} ({@link #usernameExists}) is open to any
 * visitor. Reading member data takes a credential, checked with the reader family of its kind
 * (ADR-012): a member's profile the member's own token, and the member search an admin token.
 */
@Service
@Transactional(readOnly = true)
public class MemberQueryService {

    private final UserRepository userRepository;
    private final SessionManager sessionManager;

    public MemberQueryService(UserRepository userRepository, SessionManager sessionManager) {
        this.userRepository = userRepository;
        this.sessionManager = sessionManager;
    }

    /**
     * True when a User with the given username already exists. Blank /
     * null input is treated as "doesn't exist" — the presenter handles
     * the format validation; this method is purely the existence
     * predicate.
     */
    public boolean usernameExists(String username) {
        if (username == null || username.isBlank()) return false;
        return userRepository.existsByUsername(username.trim());
    }

    /**
     * The profile projection (id, username, email) of the member the token names — backs
     * {@code MyProfileView}. The member comes from the token, so no caller can read another
     * member's profile. Returns a {@link MemberDTO}, never the domain object or anything sensitive
     * (no passwordHash).
     *
     * @param memberToken the signed-in member's JWT
     * @throws InvalidTokenException for a missing, malformed, revoked or admin token
     * @throws com.ticketing.system.shared.exception.SessionExpiredException for an expired token
     * @throws com.ticketing.system.shared.exception.UserNotFoundException if the member no longer exists
     */
    public MemberDTO getMemberProfile(String memberToken) {
        if (!sessionManager.validateToken(memberToken)) {
            throw new InvalidTokenException("missing token");
        }
        User u = userRepository.getUserById(sessionManager.extractUserId(memberToken));
        return new MemberDTO(u.getUserId(), u.getUsername(), u.getEmail());
    }

    /**
     * Members whose username contains the given (case-insensitive) substring, sorted by
     * username. Blank input yields an empty list. Backs the admin "Send Messages" recipient
     * search (II.6.3.2) — returns only id + username, never a domain object.
     *
     * @param adminToken the signed-in admin's JWT
     * @param query      the substring to look for
     * @throws InvalidTokenException for a missing, malformed, revoked or member token
     * @throws com.ticketing.system.shared.exception.SessionExpiredException for an expired token
     */
    public List<MemberSearchResultDTO> searchByUsername(String adminToken, String query) {
        if (!sessionManager.validateAdminToken(adminToken)) {
            throw new InvalidTokenException("missing token");
        }
        if (query == null || query.isBlank()) return List.of();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return userRepository.findAll().stream()
                .filter(u -> u.getUsername() != null && u.getUsername().toLowerCase(Locale.ROOT).contains(needle))
                .map(u -> new MemberSearchResultDTO(u.getUserId(), u.getUsername()))
                .sorted(Comparator.comparing(MemberSearchResultDTO::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
