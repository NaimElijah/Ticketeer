/**
 * Sales &amp; Ticketing bounded context (the platform's core transactional context).
 *
 * <p>Owns the reserve &rarr; checkout &rarr; ticket &rarr; refund lifecycle: the {@code ActiveOrder}
 * (cart), {@code OrderReceipt}, and {@code Ticket} aggregates.
 * Hexagonal layout: {@code domain}, {@code application.port.out} (driven ports
 * {@code ActiveOrderRepository}, {@code OrderReceiptRepository}, {@code TicketRepository},
 * {@code PaymentGateway}, {@code TicketIssuer}, {@code MarketGate}), {@code application.service}
 * ({@code ReservationService}, {@code CheckoutService}, {@code RefundService},
 * {@code EventCancellationRefundService}), {@code adapter.in.event} (catalog's cancellation event),
 * {@code adapter.out.persistence}, {@code adapter.out.wsep} (WSEP + stub gateways) and
 * {@code adapter.out.catalog} and {@code adapter.out.identity} (adapters for other contexts' ports).
 *
 * <p>Sales changes catalog inventory only through catalog's inbound {@code InventoryCommandPort}, and
 * publishes its notices as {@code shared.event} events that the notifications context consumes after
 * commit (ADR-004). The checkout also uses catalog's {@code EventRepository} port directly, for the
 * shared event lock a sale holds (ADR-015). The market gate is inverted: sales queries the
 * {@code MarketGate} outbound port, which governance implements.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sales & Ticketing", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.sales;
