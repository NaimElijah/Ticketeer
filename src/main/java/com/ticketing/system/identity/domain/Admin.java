package com.ticketing.system.identity.domain;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Aggregate root for a System Admin (not a company-side role).
 *
 * <p>UC-1 (I.1.4) requires the system to verify at least one Admin exists at startup,
 * auto-creating a default if none.
 *
 * <p>The id is assigned, never {@code @GeneratedValue} (the constructor rejects an id
 * {@code <= 0}). An admin and a member can hold the same id number (ADR-012). {@code @Version}
 * drives optimistic locking and lets Spring Data tell a new entity (version {@code null}) from a
 * loaded one. The protected no-arg constructor exists so Hibernate can hydrate instances; the
 * public constructor still enforces the invariants for application-created admins.
 */
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

    /**
     * Constructs an admin with the given assigned id and credentials, enforcing the invariants.
     *
     * @param id           the admin id; positive
     * @param username     the sign-in name, non-blank
     * @param passwordHash the stored password hash, non-blank; never the raw password
     * @param isDefault    whether this is the default admin that platform initialisation creates
     *                     when none exists
     * @throws IllegalStateException if {@code id} is not positive, or {@code username} or
     *     {@code passwordHash} is {@code null} or blank
     */
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

    /** Whether this is the default admin that platform initialisation creates when none exists. */
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
