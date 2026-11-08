package com.ticketing.system.ui.security;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.internal.ExecutionContext;
import com.vaadin.flow.internal.StateTree;
import com.vaadin.flow.server.Command;

/**
 * A headless {@link UI} for the guard and error-handler tests, so a toast those classes open can be read. It
 * runs {@link #access} at once, as Vaadin does for the thread that holds the session lock, and keeps what a
 * toast asks to run before the response instead of queueing it in a state tree that has no session;
 * {@link #toasts()} runs it, which attaches the toasts. Set it current with {@link UI#setCurrent} and clear
 * that after the test.
 */
final class ToastRecordingUi extends UI {

    private final List<SerializableConsumer<ExecutionContext>> recorded = new ArrayList<>();

    @Override
    public Future<Void> access(Command command) {
        command.execute();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public StateTree.ExecutionRegistration beforeClientResponse(Component component,
                                                                SerializableConsumer<ExecutionContext> execution) {
        recorded.add(execution);
        return () -> recorded.remove(execution);
    }

    /** The text of each toast opened so far, in the order opened. */
    List<String> toasts() {
        List<SerializableConsumer<ExecutionContext>> due = List.copyOf(recorded);
        recorded.clear();
        due.forEach(execution -> execution.accept(new ExecutionContext(this, false)));
        return getChildren()
            .filter(Notification.class::isInstance)
            .map(ToastRecordingUi::text)
            .toList();
    }

    // A toast holds its text in a Span, beside a close button whose icon is a Span with no text.
    private static String text(Component toast) {
        List<String> texts = new ArrayList<>();
        collectSpans(toast, texts);
        return texts.stream().findFirst().orElse("");
    }

    private static void collectSpans(Component component, List<String> texts) {
        if (component instanceof Span span && !(span instanceof LkIcon) && !span.getText().isBlank()) {
            texts.add(span.getText());
        }
        component.getChildren().forEach(child -> collectSpans(child, texts));
    }
}
