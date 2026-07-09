package com.ticketing.system.organization.application.dto;
import com.ticketing.system.organization.application.service.CompanyManagementService;

import com.ticketing.system.organization.domain.Permission;
import java.util.List;

// Input to CompanyManagementService.editManagerPermissions() (UC-24 step 3 — edit).
// Replaces the entire permission set for the target manager.
public record PermissionEditDTO(
        int companyId,
        int targetUserId,
        List<Permission> newPermissions) {
}
