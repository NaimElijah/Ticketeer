package com.ticketing.system.shared.dto;

import java.time.LocalDate;

// Input to MessagingService.viewAllComplaints() (admin queue).
// All fields nullable — represents an absent filter.
public record ComplaintFilterDTO(
    String status,                   // ConversationStatus value as string
    Integer memberId,
    LocalDate fromDate,
    LocalDate toDate
) {}
