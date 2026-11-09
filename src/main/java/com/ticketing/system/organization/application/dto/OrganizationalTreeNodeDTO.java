package com.ticketing.system.organization.application.dto;

import com.ticketing.system.organization.domain.Permission;
import java.util.List;

/**
 * Output of {@code CompanyManagementService.viewOrganizationalTree} and {@code adminViewOrgTree} (UC-25): one
 * member in a company's role tree. The structure is recursive, and each node's children are the members it appointed.
 *
 * @param userId              the member's user id
 * @param username            the member's username
 * @param role                the name of the member's {@code CompanyRole}: {@code "Owner"} or {@code "Manager"}
 * @param isFounder           whether the member founded the company
 * @param grantedPermissions  the manager's permissions; empty for an owner, who holds them all
 * @param appointedByThisUser the members this member appointed
 */
public record OrganizationalTreeNodeDTO(
        int userId,
        String username,
        String role,
        boolean isFounder,
        List<Permission> grantedPermissions,
        List<OrganizationalTreeNodeDTO> appointedByThisUser) {
}
