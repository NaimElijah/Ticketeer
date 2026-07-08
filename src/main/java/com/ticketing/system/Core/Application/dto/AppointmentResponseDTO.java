package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.organization.application.service.CompanyManagementService;

// Input to CompanyManagementService.respondToAppointment() (UC-23 / UC-24 step 2).
public record AppointmentResponseDTO(
    int companyId,
    boolean accept                       // true = ACTIVE, false = REJECTED
) {}
