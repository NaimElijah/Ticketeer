package com.ticketing.system.shared.dto;

import java.util.List;

// Generic pagination wrapper — used wherever a list result might be large
// (UC-7 search, UC-22/31 history, UC-25 large org trees).
// Page numbering is 0-indexed.
public record PageDTO<T>(
    List<T> items,
    int pageNumber,
    int pageSize,
    long totalItemCount,
    int totalPageCount
) {}
