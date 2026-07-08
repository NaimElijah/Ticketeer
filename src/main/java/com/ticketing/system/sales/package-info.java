/**
 * Sales &amp; Ticketing bounded context (the platform's core transactional context).
 *
 * <p>Owns the reserve &rarr; checkout &rarr; ticket &rarr; refund lifecycle: the {@code ActiveOrder}
 * (cart), {@code OrderReceipt}, and {@code Ticket} aggregates plus the purchase-policy model.
 * Hexagonal layout: {@code domain}, {@code application.port.out} (driven ports
 * {@code ActiveOrderRepository}, {@code OrderReceiptRepository}, {@code TicketRepository},
 * {@code PaymentGateway}, {@code TicketIssuer}), {@code application.service}
 * ({@code ReservationService}, {@code CheckoutService}, {@code RefundService}),
 * {@code adapter.out.persistence} and {@code adapter.out.wsep} (WSEP + stub gateways).
 *
 * <p>Migration note: this step is a mechanical relocation. Several cross-context couplings remain
 * transitional and are addressed in dedicated follow-up steps: sales still loads catalog {@code Event}
 * and mutates its inventory directly (&rarr; an inbound inventory command port on catalog), checks the
 * market gate via {@code SystemAdminService} (&rarr; a governance port), and notifies synchronously
 * (&rarr; domain events). Module-boundary verification is switched on at Step 10.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Sales & Ticketing")
package com.ticketing.system.sales;
