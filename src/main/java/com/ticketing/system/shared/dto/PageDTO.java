package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Generic pagination wrapper. Nothing uses it today. Page numbering is 0-indexed.
 *
 * @param <T>            the item type
 * @param pageNumber     the 0-indexed number of this page
 * @param totalItemCount how many items there are in all pages
 * @param totalPageCount how many pages there are
 */
public record PageDTO<T>(
    List<T> items,
    int pageNumber,
    int pageSize,
    long totalItemCount,
    int totalPageCount
) {}
