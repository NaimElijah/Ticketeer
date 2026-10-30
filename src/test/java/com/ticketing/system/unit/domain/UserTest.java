package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.support.BaseDomainTest;

/** {@link User}'s username fits the column that stores it (#186). */
class UserTest extends BaseDomainTest {

    // users.username is varchar(255) (V1__baseline_schema.sql); a longer username must be refused by
    // the domain, not by the database insert.
    private static final int USERNAME_LIMIT = 255;
    private static final int USER_ID = 1;
    private static final String EMAIL = "member@example.com";
    private static final String PASSWORD_HASH = "STORED_HASH";
    private static final int AGE = 30;

    @Test
    void GivenUsernameOf256Characters_WhenConstruct_ThenRefusedNamingTheLimit() {
        String username = "u".repeat(USERNAME_LIMIT + 1);

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> new User(USER_ID, username, EMAIL, PASSWORD_HASH, AGE));

        assertTrue(ex.getMessage().contains(String.valueOf(USERNAME_LIMIT)),
                () -> "the refusal names the " + USERNAME_LIMIT + "-character limit, was: " + ex.getMessage());
    }

    @Test
    void GivenUsernameOf255Characters_WhenConstruct_ThenAccepted() {
        String username = "u".repeat(USERNAME_LIMIT);

        User user = track(new User(USER_ID, username, EMAIL, PASSWORD_HASH, AGE));

        assertEquals(username, user.getUsername());
    }
}
