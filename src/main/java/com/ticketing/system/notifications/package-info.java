/**
 * Notifications bounded context.
 *
 * <p>Owns user notifications and their delivery. Aggregate: {@code Notification}. Hexagonal layout:
 * {@code domain}; {@code application.port.in} (the inbound {@code INotificationService} that other
 * contexts call to raise notifications); {@code application.port.out} ({@code NotificationRepository},
 * {@code PushNotificationService}); {@code application.service} ({@code NotificationService},
 * {@code NotificationDispatchService}); {@code adapter.out.persistence} and {@code adapter.out.push}
 * (the in-memory push adapter).
 *
 * <p>Migration note: this step is a mechanical relocation. The inbound port keeps its legacy
 * {@code INotificationService} name for now (dropping the prefix would collide with the
 * {@code NotificationService} implementation); it is renamed to a use-case name in the dedicated
 * event-driven step, where the synchronous {@code notify*} calls become domain-event subscriptions.
 * Module-boundary verification is switched on at Step 10.
 */
package com.ticketing.system.notifications;
