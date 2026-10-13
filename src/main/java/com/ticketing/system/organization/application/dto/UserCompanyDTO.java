package com.ticketing.system.organization.application.dto;

import java.util.List;

import com.ticketing.system.organization.domain.Permission;

/**
 * Read model for a user's membership in one production company.
 */
public record UserCompanyDTO(
        int companyId,
        String name,
        String description,
        String contactEmail,
        String role,
        String status,
        int members,
        int activeEvents,
        List<Permission> managerPermissions) {

    public String idAsString() {
        return String.valueOf(companyId);
    }
}
