package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Generic pagination wrapper. Page numbering is 0-indexed.
 *
 * @param <T>            the item type
 * @param pageNumber     the 0-indexed number of this page
 */
public record PageDTO<T>(
    List<T> items,
    int pageNumber,
    int pageSize,
    long totalItemCount,
    int totalPageCount
) {}
