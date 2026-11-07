package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.identity.domain.Admin;

/** {@link Admin}'s fields hold what an admin is (#189), and its password hash can be replaced, never with nothing. */
class AdminTest {

    private static final String STORED_HASH = "STORED_HASH";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenAdmin_WhenItsPasswordHashIsChangedToNothing_ThenRefusedAndTheHashIsKept(String missingHash) {
        Admin admin = new Admin(1, "admin", STORED_HASH, true);

        assertThrows(IllegalArgumentException.class, () -> admin.changePasswordHash(missingHash));

        assertEquals(STORED_HASH, admin.getPasswordHash(), "a refused change leaves the stored hash");
    }

    @ParameterizedTest(name = "id={0}")
    @ValueSource(ints = {0, -1})
    void GivenAnIdBelowOne_WhenConstructed_ThenRefused(int id) {
        assertThrows(IllegalStateException.class, () -> new Admin(id, "admin", STORED_HASH, true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenNoUsername_WhenConstructed_ThenRefused(String username) {
        assertThrows(IllegalStateException.class, () -> new Admin(1, username, STORED_HASH, true));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenNoPasswordHash_WhenConstructed_ThenRefused(String passwordHash) {
        assertThrows(IllegalStateException.class, () -> new Admin(1, "admin", passwordHash, true));
    }

    @Test
    void GivenValidFields_WhenConstructed_ThenTheAdminHoldsThem() {
        Admin admin = new Admin(1, "admin", STORED_HASH, true);

        assertEquals(1, admin.getId());
        assertEquals("admin", admin.getUsername());
        assertEquals(STORED_HASH, admin.getPasswordHash());
    }
}
