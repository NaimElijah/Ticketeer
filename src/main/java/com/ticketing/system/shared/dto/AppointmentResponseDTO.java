package com.ticketing.system.shared.dto;

// Input to CompanyManagementService.respondToAppointment() (UC-23 / UC-24 step 2).
public record AppointmentResponseDTO(
    int companyId,
    boolean accept                       // true = ACTIVE, false = REJECTED
) {}
