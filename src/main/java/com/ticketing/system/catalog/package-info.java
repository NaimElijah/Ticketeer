/**
 * Catalog &amp; Events bounded context.
 *
 * <p>Owns the event catalog and its inventory: the {@code Event} aggregate (venue map, seated/standing
 * zones, seats, show dates, discount policy) and the browse/search and event-management use cases.
 * Hexagonal layout: {@code domain}, {@code application.port.out} (the {@code EventRepository} driven
 * port), {@code application.service} ({@code EventManagementService}, {@code CatalogService}),
 * {@code adapter.out.persistence}.
 *
 * <p>Migration note: sales still loads {@code Event} and mutates its inventory directly (transitional).
 * The inbound inventory command port (catalog owns reserve/release) is introduced with the sales
 * rewiring in Step 5; module-boundary verification is switched on at Step 10.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Catalog & Events", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.catalog;
