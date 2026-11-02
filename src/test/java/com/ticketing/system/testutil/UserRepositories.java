package com.ticketing.system.testutil;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/** User repository doubles for tests that need a member to exist, not to be a particular one. */
public final class UserRepositories {

    private UserRepositories() { }

    /**
     * A stand-in for the identity port that answers every user id with a member of the given age. The
     * real port never returns {@code null}: it throws {@code UserNotFoundException} for a missing user,
     * so a bare mock, which returns {@code null}, is no faithful double of it.
     *
     * @param age the age every member reports
     * @return a mock of the port, holding one real member
     */
    public static UserRepository everyMemberExistsAged(int age) {
        User member = new User(1, "member", "member@example.test", "unused-password-hash", age);
        UserRepository users = mock(UserRepository.class);
        when(users.getUserById(anyInt())).thenReturn(member);
        return users;
    }
}
