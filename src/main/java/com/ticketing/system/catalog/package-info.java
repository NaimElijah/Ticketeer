/**
 * Catalog &amp; Events bounded context.
 *
 * <p>Owns the event catalog and its inventory: the {@code Event} aggregate (venue map, seated/standing
 * zones, seats, show dates, discount policy) and the browse/search and event-management use cases.
 * Hexagonal layout: {@code domain}, {@code application.port.in} (the inbound
 * {@code InventoryCommandPort} and {@code CatalogEventDisplayPort}), {@code application.port.out} (the
 * {@code EventRepository} and {@code EventSalesHistoryPort} driven ports), {@code application.service}
 * ({@code EventManagementService}, {@code CatalogService}, {@code InventoryService},
 * {@code EventDisplayQueryService}), {@code adapter.out.persistence}, and {@code adapter.out.organization}
 * (answers organization's {@code CompanyEventStatsPort}).
 *
 * <p>Sales changes inventory through {@code InventoryCommandPort}, in ids and DTOs, and reads event
 * display data through {@code CatalogEventDisplayPort}. {@code CheckoutService} also takes the
 * {@code EventRepository} port, for the event buyer lock. Cancelling an event publishes
 * {@code EventCancelledEvent}, which sales consumes to refund; sales implements
 * {@code EventSalesHistoryPort}, so catalog imports no sales type.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Catalog & Events", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.catalog;
