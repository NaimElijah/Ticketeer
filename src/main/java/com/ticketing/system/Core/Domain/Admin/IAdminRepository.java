package com.ticketing.system.Core.Domain.Admin;

import java.util.List;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the Admin aggregate.
public interface IAdminRepository extends IRepository<Admin, Integer> {

    Admin findById(int adminId);

    Admin findByUsername(String username);

    /** All system admins. Backs messaging notifications to the admin queue (II.3.3 / II.6.3.1). */
    List<Admin> findAll();

    // Used by SystemAdminService.initializePlatform() per UC-1 / I.1.4
    // (default-admin existence check).
    boolean existsAny();

    void save(Admin admin);
}
