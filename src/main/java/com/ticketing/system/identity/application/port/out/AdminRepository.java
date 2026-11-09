package com.ticketing.system.identity.application.port.out;
import com.ticketing.system.identity.domain.Admin;

import java.util.List;

import com.ticketing.system.shared.IRepository;

/** Aggregate-root entry point for the Admin aggregate. */
public interface AdminRepository extends IRepository<Admin, Integer> {

    /**
     * By-id lookup.
     *
     * @return the admin with that id, or {@code null} if there is none
     */
    Admin findById(int adminId);

    /**
     * By-username lookup, matching the name exactly.
     *
     * @return the admin holding {@code username}, or {@code null} if none does; {@code null} for a
     *     {@code null} username
     */
    Admin findByUsername(String username);

    /** All system admins. Read by the boot-time password rotation (ADR-014). */
    List<Admin> findAll();

    /**
     * Whether at least one admin exists. Used by {@code SystemAdminService} per UC-1 / I.1.4 (the
     * default-admin existence check).
     */
    boolean existsAny();

    /**
     * Stores a new admin, or saves a stored one again (the boot-time password rotation, ADR-014).
     *
     * @throws com.ticketing.system.shared.exception.DuplicateUsernameException if another admin, one
     *     with a different id, already holds {@code admin}'s username; the stored admin is then unchanged
     */
    void save(Admin admin);
}
