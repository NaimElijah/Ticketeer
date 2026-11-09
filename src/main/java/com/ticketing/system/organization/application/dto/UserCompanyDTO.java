package com.ticketing.system.organization.application.dto;

import java.util.List;

import com.ticketing.system.organization.domain.Permission;

/**
 * Read model for a user's membership in one production company.
 *
 * @param contactEmail      always the empty string today; no contact email is stored for a company
 * @param role              the member's role in the company
 * @param status            the name of the company's {@code CompanyStatus}
 * @param members           the number of owners plus managers
 * @param activeEvents      the number of the company's events that are scheduled, on sale or sold out
 * @param managerPermissions the member's permissions when they are a manager; empty for an owner, who holds
 *                          them all
 */
public record UserCompanyDTO(
        int companyId,
        String name,
        String description,
        String contactEmail,
        MembershipRole role,
        String status,
        int members,
        int activeEvents,
        List<Permission> managerPermissions) {

    /**
     * Returns {@link #companyId()} as decimal text.
     */
    public String idAsString() {
        return String.valueOf(companyId);
    }
}
