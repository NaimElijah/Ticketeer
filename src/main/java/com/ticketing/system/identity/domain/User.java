package com.ticketing.system.identity.domain;

import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.shared.domain.TextLimits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * A registered member: the aggregate root for a member's login identity.
 *
 * <p>{@code userId} is an assigned {@code @Id}, minted by {@code UserRepository.nextId()}, never
 * {@code @GeneratedValue}. Username and email are each unique ({@code uk_users_username},
 * {@code uk_users_email}). {@code @Version} drives optimistic locking. The protected no-arg
 * constructor exists so Hibernate can hydrate instances; the public constructor still enforces the
 * invariants.
 *
 * <p>The user holds no company appointments and imports nothing from {@code organization}: those
 * belong to the {@code organization} context's {@code CompanyAppointment} aggregate.
 */
@Entity
@Table(name = "users",
        uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
public class User implements InvariantChecked {

    @Id
    private int userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private int age;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected User() { }

    /**
     * Constructs a user with the given assigned id, credentials and age, enforcing the invariants.
     *
     * @param userId   the id minted by {@code UserRepository.nextId()}; positive
     * @param username the login name, non-blank, at most {@value TextLimits#MAX_TEXT_LENGTH}
     *                 characters; the character rules are applied at registration, not here
     * @param email    the contact address, non-blank; its format is checked at registration, not here
     * @param password the stored password hash, non-blank; never the raw password
     * @param age      whole years, not negative; feeds age-gated purchase policies
     * @throws com.ticketing.system.shared.exception.BusinessRuleViolationException if
     *     {@code username} is longer than the limit
     * @throws IllegalStateException if {@code userId} is not positive, {@code username},
     *     {@code email} or {@code password} is {@code null} or blank, or {@code age} is negative
     */
    public User(int userId, String username, String email, String password, int age) {
        TextLimits.requireFits("Username", username);
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.password = password;   // already hashed: hashing belongs to the application layer
        this.age = age;
        checkInvariants();
    }

    // ---------------------------------------------------------------------------
    // Accessors.
    // ---------------------------------------------------------------------------

    /** The user's assigned id. */
    public int getUserId() {
        return userId;
    }

    /** The user's display / login name. */
    public String getUsername() {
        return username;
    }

    /** Email registered at sign-up. Used for uniqueness checks. UC-11. */
    public String getEmail() {
        return email;
    }

    /** The user's age (feeds age-gated purchase policies). */
    public int getAge() {
        return age;
    }

    /**
     * The stored password hash. UC-12. The Application layer owns the password hasher and does the
     * raw-vs-hash comparison (mirrors {@code Admin.getPasswordHash()}), keeping this entity free of
     * any application or adapter dependency.
     */
    public String getPasswordHash() {
        return password;
    }

    /** Validates the user's invariants (positive id, non-blank credentials, non-negative age). */
    @Override
    public void checkInvariants() {
        if (userId <= 0) {
            throw new IllegalStateException("User invariant violated: userId must be positive (was " + userId + ")");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("User invariant violated: username must be non-blank");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("User invariant violated: email must be non-blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("User invariant violated: password hash must be non-blank");
        }
        if (age < 0) {
            throw new IllegalStateException("User invariant violated: age cannot be negative");
        }
    }
}
