package com.ticketing.system.ui.presenters.messaging;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import com.ticketing.system.shared.dto.ConversationDTO;

/**
 * The threads one inbox view has opened, each with the time of its latest message when it was opened.
 *
 * <p>The inbox presenters are shared singletons, so a view's opened set lives in the view: the view
 * creates one, keeps it for its own lifetime, and passes it to the presenter's {@code open} and
 * {@code badge}, which make every decision about it. Its methods are package-private, so a view can
 * hold it but not change it (#187).
 */
public final class OpenedThreads {

    private final Map<String, LocalDateTime> lastMessageAtWhenOpened = new HashMap<>();

    void record(ConversationDTO conversation) {
        lastMessageAtWhenOpened.put(conversation.conversationId(), conversation.lastMessageAt());
    }

    // True while the thread has had no newer message since this view opened it. A newer message means
    // the row's unread count is fresh from the server, not the count the view showed before the open.
    boolean isOpenAsOf(ConversationDTO conversation) {
        LocalDateTime openedAt = lastMessageAtWhenOpened.get(conversation.conversationId());
        return openedAt != null && !conversation.lastMessageAt().isAfter(openedAt);
    }
}
