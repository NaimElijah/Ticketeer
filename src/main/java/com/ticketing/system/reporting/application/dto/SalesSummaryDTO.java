package com.ticketing.system.reporting.application.dto;

import java.util.Optional;

/**
 * Headline sales figures over a list of order records (UC-22, UC-31). Only lines that are not
 * refunded count as sold (#21).
 *
 * @param revenue        the sum of the prices of the lines not refunded
 * @param ticketsSold    how many lines are not refunded
 * @param avgOrderValue  revenue per order that still has a line not refunded; 0 when none has
 * @param topEvent       the name of the event with the highest revenue; empty when nothing is sold,
 *                       and when no sold line carries an event name (lines with no resolved event
 *                       name are not ranked)
 * @param refundedOrders how many orders have at least one refunded line
 */
public record SalesSummaryDTO(double revenue, int ticketsSold, double avgOrderValue, Optional<String> topEvent,
        long refundedOrders) {
}
