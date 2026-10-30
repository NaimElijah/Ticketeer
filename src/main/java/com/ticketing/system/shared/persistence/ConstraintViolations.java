package com.ticketing.system.shared.persistence;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells which named constraint an integrity violation broke, so a Jpa adapter can turn the violation of
 * one constraint into the domain's own exception and let every other violation propagate unchanged.
 */
public final class ConstraintViolations {

    private ConstraintViolations() { }

    /**
     * Matched by containment, ignoring case: PostgreSQL reports the constraint as named, while H2 reports
     * the name of the index behind it, schema-qualified and suffixed ({@code PUBLIC.UK_USERS_USERNAME_INDEX_4}).
     *
     * @param violation  what the flush or the commit threw
     * @param constraint the constraint's name as the migration gives it, in lower case
     * @return whether the first Hibernate constraint violation in the cause chain names {@code constraint}
     */
    public static boolean violates(DataIntegrityViolationException violation, String constraint) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                String name = constraintViolation.getConstraintName();
                return name != null && name.toLowerCase(Locale.ROOT).contains(constraint);
            }
        }
        return false;
    }
}
