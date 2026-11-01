package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter.MarkReadOutcome;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.vaadin.flow.component.html.NativeButton;

/**
 * The bell shows an alert read only once the store has it read, and says why when it does not: a
 * refused read leaves the item unread with the reason as a failure toast, and "Mark All Read" stops at
 * the first refusal, since with the store down every later call would wait out its own timeout and
 * fail the same way (#187).
 *
 * <p>The bell's rows and its "Mark All Read" button are clicked through Vaadin's own event path
 * ({@link InboxViewHarness#click}); the store is the {@code markRead} function the bell is built with.
 */
class NotificationBellComponentTest {

    private static final String REFUSAL = "refusal-reason-under-test";
    private static final String UNREAD = "SENT";
    private static final String READ = "READ";
    private static final String ROW_CLASS = "lk-notif-item";
    private static final String MARK_ALL = "Mark All Read";
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);

    private InboxViewHarness harness;
    private final List<String> attempted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, 42, "member", false));
        harness = new InboxViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenTheStoreSavesTheRead_WhenARowIsClicked_ThenTheItemIsShownRead() {
        NotificationSession.store(List.of(unread("n1", 0)));
        NotificationBellComponent bell = bell(id -> new MarkReadOutcome.Success());

        InboxViewHarness.click(rows(bell).get(0));

        assertEquals(Map.of("n1", READ), statuses());
        assertEquals(List.of(), harness.toasts());
    }

    @Test
    void GivenTheStoreRefusesTheRead_WhenARowIsClicked_ThenTheItemStaysUnreadAndTheReasonIsShown() {
        NotificationSession.store(List.of(unread("n1", 0)));
        NotificationBellComponent bell = bell(id -> new MarkReadOutcome.Failure(REFUSAL));

        InboxViewHarness.click(rows(bell).get(0));

        assertEquals(Map.of("n1", UNREAD), statuses(), "a read the store refused must not clear the badge");
        assertEquals(List.of(REFUSAL), harness.toasts(), "the refusal's reason should be shown as a toast");
    }

    @Test
    void GivenTheSecondOfThreeReadsIsRefused_WhenMarkAllRead_ThenTheFirstIsReadTheThirdIsNotAttemptedAndTheReasonIsShown() {
        NotificationSession.store(List.of(unread("n1", 0), unread("n2", 1), unread("n3", 2)));
        NotificationBellComponent bell = bell(id -> "n2".equals(id)
                ? new MarkReadOutcome.Failure(REFUSAL)
                : new MarkReadOutcome.Success());

        InboxViewHarness.click(markAll(bell));

        assertEquals(List.of("n1", "n2"), attempted, "mark-all should stop at the first refused read");
        assertEquals(Map.of("n1", READ, "n2", UNREAD, "n3", UNREAD), statuses());
        assertEquals(List.of(REFUSAL), harness.toasts());
    }

    @Test
    void GivenNoAgeRenderer_WhenTheBellIsBuilt_ThenItRefusesAtOnce() {
        assertThrows(NullPointerException.class,
                () -> new NotificationBellComponent(id -> new MarkReadOutcome.Success(), null));
    }

    // Records each id the bell tries to mark, then answers as the store would.
    private NotificationBellComponent bell(Function<String, MarkReadOutcome> store) {
        return new NotificationBellComponent(id -> {
            attempted.add(id);
            return store.apply(id);
        }, createdAt -> "");
    }

    // The bell lists the newest first, so minutesAgo orders the rows top to bottom.
    private static NotificationDTO unread(String id, int minutesAgo) {
        return new NotificationDTO(id, "DIRECT_MESSAGE", UNREAD, "Message " + id, NOW.minusMinutes(minutesAgo));
    }

    private static List<NativeButton> rows(NotificationBellComponent bell) {
        return InboxViewHarness.find(bell, NativeButton.class).stream()
                .filter(button -> button.hasClassName(ROW_CLASS))
                .toList();
    }

    private static NativeButton markAll(NotificationBellComponent bell) {
        return InboxViewHarness.find(bell, NativeButton.class).stream()
                .filter(button -> MARK_ALL.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, String> statuses() {
        return NotificationSession.getAll().stream()
                .collect(Collectors.toMap(NotificationDTO::notificationId, NotificationDTO::status));
    }
}
