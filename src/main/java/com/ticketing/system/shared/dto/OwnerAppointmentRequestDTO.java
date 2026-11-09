package com.ticketing.system.shared.dto;

/**
 * Input to {@code CompanyManagementService.appointOwner} (UC-23 step 1). Owners get all permissions
 * automatically per II.4.8.1, so there is no permission list.
 */
public record OwnerAppointmentRequestDTO(
    int companyId,
    int targetUserId
) {}
