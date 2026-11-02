package com.ticketing.system.acceptance;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.NotificationType;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

// ---- An outreach to more recipients than the notification queue holds drops alerts (#187) ----
//
// Notices are stored after commit on one notify- thread whose queue holds 1,000; beyond that a
// notice is dropped and logged (ADR-004, NotificationAsyncConfig). An outreach that publishes one
// notice per recipient therefore loses every recipient past the queue, and any other user's notice
// that arrives while the queue is full. Drives the real executor, on the Memory repositories.
//
// The members are saved straight through the UserRepository port: registering 1,500 through
// AuthenticationService would hash 1,500 passwords. Both this and the admin added to the admin pool
// change shared state, so the context is rebuilt after the class.
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessagingOutreachNotificationAcceptanceTest {

    // More than the notify- executor's queue of 1,000 plus its one running task.
    private static final int RECIPIENTS = 1_500;
    private static final int MEMBER_AGE = 30;
    private static final int MISSING_SAMPLE = 5;
    private static final String PASSWORD = "Password1";
    private static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(20);

    @Autowired private AuthenticationService authService;
    @Autowired private MessagingService messagingService;
    @Autowired private UserRepository userRepository;
    @Autowired private AdminRepository adminRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private NotificationRepository notificationRepository;

    @Test
    void GivenMoreRecipientsThanTheNotificationQueueHolds_WhenAnAdminSendsOutreach_ThenEveryRecipientGetsOneNewMessageNotification() {
        AuthTokenDTO admin = registerAdmin("outreach.flood.admin");
        List<Integer> recipients = saveMembers(RECIPIENTS);

        messagingService.sendOutreach(admin.token(), new OutreachRequestDTO(
                "Venue change", "The show moves to Hall B.", recipients, false, false));

        await().atMost(DELIVERY_DEADLINE).untilAsserted(() -> {
            List<Integer> withoutExactlyOne = recipients.stream()
                    .filter(id -> newMessageNotificationsFor(id) != 1)
                    .toList();
            assertTrue(withoutExactlyOne.isEmpty(), () -> withoutExactlyOne.size() + " of " + RECIPIENTS
                    + " recipients do not hold exactly one new-message notification, e.g. member ids "
                    + withoutExactlyOne.subList(0, Math.min(MISSING_SAMPLE, withoutExactlyOne.size())));
        });
    }

    private long newMessageNotificationsFor(int userId) {
        return notificationRepository.findByRecipient(userId).stream()
                .filter(n -> n.getType() == NotificationType.DIRECT_MESSAGE)
                .count();
    }

    private List<Integer> saveMembers(int count) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = userRepository.nextId();
            String name = "flood-" + run + "-" + i;
            userRepository.save(new User(id, name, name + "@test.com", "unused-password-hash", MEMBER_AGE));
            ids.add(id);
        }
        return ids;
    }

    // As MessagingAcceptanceTest does: a registered member promoted into the admin pool, then signed in
    // there, so the token carries the ADMIN role the messaging gate requires.
    private AuthTokenDTO registerAdmin(String baseName) {
        String name = baseName + "-" + UUID.randomUUID().toString().substring(0, 8);
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", PASSWORD, sid, MEMBER_AGE));
        int userId = authService.login(new LoginRequestDTO(name, PASSWORD, sid)).authToken().userId();
        adminRepository.save(new Admin(userId, name, passwordHasher.hash(PASSWORD), false));
        return authService.signInAsAdmin(name, PASSWORD);
    }
}
