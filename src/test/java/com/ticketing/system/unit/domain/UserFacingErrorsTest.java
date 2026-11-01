package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.UserFacingErrors;

import jakarta.persistence.PersistenceException;

// The UI and the reservation failure notices both apply this rule, so it is pinned once, here.
class UserFacingErrorsTest {

    private static final String RULE_TEXT = "rule-text-under-test";
    private static final String BLANK = "   ";

    static Stream<Arguments> ruleExceptionsWithText() {
        return Stream.of(
                Arguments.of("DomainException", new BusinessRuleViolationException(RULE_TEXT)),
                Arguments.of("IllegalArgumentException", new IllegalArgumentException(RULE_TEXT)),
                Arguments.of("IllegalStateException", new IllegalStateException(RULE_TEXT)));
    }

    static Stream<Arguments> failuresAUserMayNotRead() {
        return Stream.of(
                Arguments.of("DomainException without a message", new BusinessRuleViolationException(null)),
                Arguments.of("DomainException with an empty message", new BusinessRuleViolationException("")),
                Arguments.of("DomainException with a blank message", new BusinessRuleViolationException(BLANK)),
                Arguments.of("IllegalArgumentException without a message", new IllegalArgumentException()),
                Arguments.of("IllegalArgumentException with a blank message", new IllegalArgumentException(BLANK)),
                Arguments.of("IllegalStateException without a message", new IllegalStateException()),
                Arguments.of("IllegalStateException with a blank message", new IllegalStateException(BLANK)),
                Arguments.of("RuntimeException", new RuntimeException(RULE_TEXT)),
                Arguments.of("NullPointerException", new NullPointerException(RULE_TEXT)),
                Arguments.of("PersistenceException", new PersistenceException(RULE_TEXT)),
                Arguments.of("DataAccessException", new DataAccessResourceFailureException(RULE_TEXT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ruleExceptionsWithText")
    void GivenARuleExceptionWithText_WhenIsUserFacing_ThenTrue(String type, RuntimeException failure) {
        assertTrue(UserFacingErrors.isUserFacing(failure), type + " with text is written for the user");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresAUserMayNotRead")
    void GivenAFailureWithoutUserText_WhenIsUserFacing_ThenFalse(String type, RuntimeException failure) {
        assertFalse(UserFacingErrors.isUserFacing(failure), type + " has no text a user may read");
    }
}
