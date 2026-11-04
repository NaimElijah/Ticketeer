package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemberQueryServiceTest {

    private UserRepository userRepository;
    private MemberQueryService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new MemberQueryService(userRepository, mock(SessionManager.class));
    }

    @Test
    void GivenAnExistingUsername_WhenUsernameExists_ThenDelegatesToRepository() {
        when(userRepository.existsByUsername("naim.founder")).thenReturn(true);

        assertTrue(service.usernameExists("naim.founder"));
    }

    @Test
    void GivenAPaddedUsername_WhenUsernameExists_ThenTrimsBeforeQuerying() {
        when(userRepository.existsByUsername("naim.founder")).thenReturn(true);

        assertTrue(service.usernameExists("  naim.founder  "));
    }

    @Test
    void GivenNullOrBlank_WhenUsernameExists_ThenReturnsFalseWithoutHittingRepo() {
        assertFalse(service.usernameExists(null));
        assertFalse(service.usernameExists(""));
        assertFalse(service.usernameExists("   "));
        verify(userRepository, never()).existsByUsername(org.mockito.ArgumentMatchers.anyString());
    }
}
