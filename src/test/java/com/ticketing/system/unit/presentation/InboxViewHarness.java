package com.ticketing.system.unit.presentation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.messaging.MdConvRow;
import com.ticketing.system.ui.support.DisplayTime;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.internal.ExecutionContext;
import com.vaadin.flow.internal.StateTree;

/**
 * Builds the four inbox views headless, with a mocked presenter as {@code VaadinSmokeTest} does, and
 * reads what they show: the conversation rows, their unread badges and the toasts (#187).
 *
 * <p>A toast needs a current {@link UI}, which Vaadin asks to attach it just before the next response.
 * This UI records those requests and {@link #toasts()} runs them, which attaches the toasts; nothing
 * else of the response runs, since that would need a live session. Create one per test and
 * {@link #close()} it. {@code InboxViewHarnessTest} proves the toasts are read.
 */
final class InboxViewHarness implements AutoCloseable {

    static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);
    static final String SESSION_EXPIRED = "Your session has expired — please sign in again.";

    private static final String BADGE_CLASS = "md-conv-dot";

    private final RecordingUi ui = new RecordingUi();

    InboxViewHarness() {
        UI.setCurrent(ui);
    }

    /** A display clock fixed at {@link #NOW}, in UTC, so the rows' ages never depend on the JVM zone. */
    static DisplayTime displayTime() {
        return new DisplayTime(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
            new PlatformTimeProperties(ZoneOffset.UTC));
    }

    /** An open thread of {@code type} from a member, with one message and two unread for the viewer. */
    static ConversationDTO thread(String id, String type, String counterpartyType) {
        return thread(id, type, counterpartyType, "OPEN");
    }

    /** A thread of {@code type} in {@code status} from a member, with one message and two unread for the viewer. */
    static ConversationDTO thread(String id, String type, String counterpartyType, String status) {
        return new ConversationDTO(id, type, status, 1, "MEMBER", 7, counterpartyType,
            "Subject " + id, NOW, NOW, 2,
            List.of(new MessageDTO("m-" + id, 1, "MEMBER", "Body " + id, NOW, false)),
            "alice", "Acme");
    }

    /** The view's conversation rows, top to bottom. */
    static List<MdConvRow> rows(Component view) {
        return find(view, MdConvRow.class);
    }

    /** The row's unread badge text, or {@code null} when it shows none. */
    static String badge(MdConvRow row) {
        return find(row, Span.class).stream()
            .filter(span -> span.hasClassName(BADGE_CLASS))
            .map(Span::getText)
            .findFirst()
            .orElse(null);
    }

    /** Clicks {@code component} as the reader would. */
    static void click(Component component) {
        ComponentUtil.fireEvent(component, new ClickEvent<>(component));
    }

    /** Every component of {@code type} under {@code root}, {@code root} included, in tree order. */
    static <T extends Component> List<T> find(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    /** The text of each toast shown so far, in the order shown. */
    List<String> toasts() {
        ui.runRecorded();
        return find(ui, Notification.class).stream()
            .map(InboxViewHarness::toastText)
            .toList();
    }

    @Override
    public void close() {
        UI.setCurrent(null);
    }

    // A toast holds its text in a Span, beside a close button whose icon is a Span with no text.
    private static String toastText(Notification toast) {
        return find(toast, Span.class).stream()
            .filter(span -> !(span instanceof LkIcon) && !span.getText().isBlank())
            .map(Span::getText)
            .findFirst()
            .orElse("");
    }

    private static <T extends Component> void collect(Component component, Class<T> type, List<T> found) {
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        component.getChildren().forEach(child -> collect(child, type, found));
    }

    // Keeps what a component asks to run before the next response instead of queueing it in the state
    // tree, whose flush also runs JavaScript calls that need a session this headless UI does not have.
    private static final class RecordingUi extends UI {

        private final List<SerializableConsumer<ExecutionContext>> recorded = new ArrayList<>();

        @Override
        public StateTree.ExecutionRegistration beforeClientResponse(Component component,
                                                                    SerializableConsumer<ExecutionContext> execution) {
            recorded.add(execution);
            return () -> recorded.remove(execution);
        }

        void runRecorded() {
            List<SerializableConsumer<ExecutionContext>> due = List.copyOf(recorded);
            recorded.clear();
            due.forEach(execution -> execution.accept(new ExecutionContext(this, false)));
        }
    }
}
