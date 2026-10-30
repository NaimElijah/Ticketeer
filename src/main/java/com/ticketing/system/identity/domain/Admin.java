package com.ticketing.system.identity.domain;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// Aggregate root for the Admin aggregate (System Admin, not company-side roles).
// UC-1 (I.1.4) requires the system to verify at least one Admin exists at startup,
// auto-creating a default if none.
//
// V3: this is the first domain aggregate mapped to JPA. The id is ASSIGNED, never
// @GeneratedValue (the constructor rejects id <= 0); @Version drives optimistic
// locking and lets Spring Data tell a new entity (version == null) from a loaded
// one. Fields are non-final and a protected no-arg constructor exists purely so
// Hibernate can hydrate instances — the public constructor still enforces the
// invariants for application-created admins.
@Entity
@Table(name = "admins")
public class Admin implements InvariantChecked {

    @Id
    private int id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected Admin() { }

    public Admin(int id, String username, String passwordHash, boolean isDefault) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.isDefault = isDefault;
        checkInvariants();
    }

    public int getId() { return id; }

    public String getUsername() { return username; }

    public String getPasswordHash() { return passwordHash; }

    public boolean isDefault() { return isDefault; }

    /**
     * Replaces this admin's password hash, keeping every other field. Saving the admin again updates
     * its stored row, so call it on an admin loaded from {@code AdminRepository}: a new {@code Admin}
     * with the same id would be inserted a second time.
     *
     * @param newPasswordHash the hash of the new password, never the password itself
     * @throws IllegalArgumentException if {@code newPasswordHash} is {@code null} or blank; the
     *     stored hash is then unchanged
     */
    public void changePasswordHash(String newPasswordHash) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IllegalArgumentException("Admin password hash must be non-blank");
        }
        this.passwordHash = newPasswordHash;
    }

    @Override
    public void checkInvariants() {
        if (id <= 0) {
            throw new IllegalStateException("Admin invariant violated: id must be positive (was " + id + ")");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("Admin invariant violated: username must be non-blank");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalStateException("Admin invariant violated: passwordHash must be non-blank");
        }
    }
}
