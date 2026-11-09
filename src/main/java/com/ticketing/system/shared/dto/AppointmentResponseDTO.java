package com.ticketing.system.shared.dto;

/**
 * Input to {@code CompanyManagementService.respondToAppointment} (UC-23 / UC-24 step 2): the invited
 * member's answer to the offer a company made them.
 *
 * @param companyId the company whose pending offer is answered; must be positive
 * @param accept    {@code true} to accept, which makes the appointment ACTIVE; {@code false} to
 *                  reject it
 */
public record AppointmentResponseDTO(
    int companyId,
    boolean accept
) {}
