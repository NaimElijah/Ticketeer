package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.identity.domain.Admin;

/** {@link Admin}'s password hash can be replaced, never with nothing. */
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
}
