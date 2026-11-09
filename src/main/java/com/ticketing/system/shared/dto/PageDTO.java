package com.ticketing.system.shared.dto;

import java.util.List;

// Generic pagination wrapper. Nothing uses it today.
// Page numbering is 0-indexed.
public record PageDTO<T>(
    List<T> items,
    int pageNumber,
    int pageSize,
    long totalItemCount,
    int totalPageCount
) {}
