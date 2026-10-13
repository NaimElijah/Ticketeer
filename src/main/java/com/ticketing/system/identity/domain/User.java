package com.ticketing.system.identity.domain;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// V3: mapped to JPA. userId is an ASSIGNED @Id (minted by UserRepository.nextId(), never
// @GeneratedValue); email is unique; @Version drives optimistic locking. A protected no-arg ctor lets
// Hibernate hydrate; the public ctor still enforces the invariants.
//
// Task #20: company appointments used to live here as an owned @OneToMany collection with ~17
// membership/authorization methods, which forced the foundational identity context to import four
// organization types. That collection and those methods have been promoted to the standalone
// organization CompanyAppointment aggregate (owned by CompanyAppointmentRepository /
// CompanyMembershipService), so User now imports NOTHING from organization and the last
// identity -> organization cycle is gone.
@Entity
@Table(name = "users")
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

    /** Constructs a user with the given assigned id, credentials and age, enforcing the invariants. */
    public User(int userId, String username, String email, String password, int age) {
        this.userId = userId;       // assigned id (minted by the repository)
        this.username = username;   // display / login name
        this.email = email;         // unique contact + secondary login key
        this.password = password;   // stored password hash (hashing owned by the application layer)
        this.age = age;             // used by age-gated purchase policies
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
     * any Application/Infrastructure dependency.
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
