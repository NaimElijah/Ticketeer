package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.organization.application.service.CompanyManagementService;

import com.ticketing.system.Core.Domain.users.Permission;
import java.util.List;

// Output of CompanyManagementService.viewOrganizationalTree() (UC-25).
// Recursive structure — each node has children appointed by it.
public record OrganizationalTreeNodeDTO(
        int userId,
        String username,
        String role, // "Owner" / "Manager" — value of CompanyRole as string
        boolean isFounder,
        List<Permission> grantedPermissions, // Empty for Owners (they have all)
        List<OrganizationalTreeNodeDTO> appointedByThisUser) {
}
