package com.ticketing.system.organization.application.dto;

import com.ticketing.system.organization.domain.Permission;
import java.util.List;

/**
 * Input to {@code CompanyManagementService.editManagerPermissions} (UC-24 step 3, edit).
 *
 * @param companyId      the company the manager belongs to
 * @param targetUserId   the manager whose permissions change
 * @param newPermissions the manager's full permission set after the edit; it replaces the old set and must be
 *                       non-empty
 */
public record PermissionEditDTO(
        int companyId,
        int targetUserId,
        List<Permission> newPermissions) {
}
