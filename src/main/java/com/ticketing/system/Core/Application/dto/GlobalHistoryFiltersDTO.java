package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.governance.application.service.SystemAdminService;

import java.time.LocalDate;
import java.util.List;

// Input to SystemAdminService.viewGlobalHistory() (UC-31).
// Per II.6.4: filter by buyer, production company, or specific event.
// All fields nullable.
public record GlobalHistoryFiltersDTO(
    Integer buyerUserId,
    Integer companyId,
    List<Integer> eventIds,
    LocalDate fromDate,
    LocalDate toDate
) {}
