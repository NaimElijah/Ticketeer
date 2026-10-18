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
 * <p>Backend contexts raise notifications only by publishing {@code shared.event} notices, which
 * {@code NotificationEventListener} consumes after their transaction commits, on the {@code notify-}
 * thread (ADR-004). Two known exceptions call {@code NotificationDispatchService} directly: identity's
 * {@code AuthenticationService} delivers pending notifications at login, and bootstrap's
 * {@code NotificationPollingScheduler} pushes them on a timer. The {@code ui} reads and marks them
 * through {@code INotificationService}. That port keeps its legacy name, because dropping the prefix
 * would collide with the {@code NotificationService} implementation.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notifications", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.notifications;
