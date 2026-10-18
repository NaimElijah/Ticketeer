/**
 * Notifications bounded context.
 *
 * <p>Owns user notifications and their delivery. Aggregate: {@code Notification}. Hexagonal layout:
 * {@code domain}; {@code application.port.in} (the inbound {@code INotificationService} that raises
 * notifications); {@code application.port.out} ({@code NotificationRepository},
 * {@code PushNotificationService}); {@code application.service} ({@code NotificationService},
 * {@code NotificationDispatchService}); {@code adapter.in.event} ({@code NotificationEventListener});
 * {@code adapter.out.persistence} and {@code adapter.out.push} (the in-memory push adapter);
 * {@code config} (the {@code notify-} executor).
 *
 * <p>Other contexts never call this module: they publish {@code shared.event} notices, which
 * {@code NotificationEventListener} consumes after their transaction commits, on the {@code notify-}
 * thread (ADR-004). The inbound port keeps its legacy {@code INotificationService} name, because
 * dropping the prefix would collide with the {@code NotificationService} implementation.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notifications", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.notifications;
