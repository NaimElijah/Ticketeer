package com.ticketing.system.ui.presenters.messaging;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.ticketing.system.shared.dto.ConversationDTO;

/**
 * The threads one inbox view has opened, each with the time of its latest message when it was opened,
 * and the unread-badge rule every inbox shares.
 *
 * <p>The inbox presenters are shared singletons, so a view's opened set lives in the view: the view
 * creates one, keeps it for its own lifetime, and passes it to the presenter's {@code open} and
 * {@code badge}, which delegate every decision about it here. Its methods are package-private, so a
 * view can hold it but not change it (#187).
 */
public final class OpenedThreads {

    private final Map<String, LocalDateTime> lastMessageAtWhenOpened = new HashMap<>();

    void record(ConversationDTO conversation) {
        lastMessageAtWhenOpened.put(conversation.conversationId(), conversation.lastMessageAt());
    }

    // No badge once this view has opened the thread and no newer message has arrived since, so a
    // re-render from the same list keeps a cleared badge cleared; otherwise the unread count, and no
    // badge when that is 0.
    Optional<String> badgeFor(ConversationDTO conversation) {
        if (isOpenAsOf(conversation) || conversation.unreadCountForViewer() == 0) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(conversation.unreadCountForViewer()));
    }

    // True while the thread has had no newer message since this view opened it. A newer message means
    // the row's unread count is fresh from the server, not the count the view showed before the open.
    private boolean isOpenAsOf(ConversationDTO conversation) {
        LocalDateTime openedAt = lastMessageAtWhenOpened.get(conversation.conversationId());
        return openedAt != null && !conversation.lastMessageAt().isAfter(openedAt);
    }
}
