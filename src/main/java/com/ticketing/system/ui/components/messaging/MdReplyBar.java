package com.ticketing.system.ui.components.messaging;

import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Inline reply bar — real Vaadin {@link TextField} + primary Send
 * button. Calls back via {@link #onSend(SendHandler)} with the text on
 * submit (Enter or button click), and clears the field only when the
 * handler reports the reply sent, so a refused reply is not lost. A reply
 * over the stored length is flagged on the field and never sent.
 */
public class MdReplyBar extends Div {

    private static final String INPUT_NAME = "Reply";

    private final TextField input = new TextField();
    private final Button    send  = new Button("Send");
    private SendHandler onSend;

    public MdReplyBar() {
        addClassName("md-reply");
        input.setPlaceholder("Write a reply…");
        // A placeholder is no accessible name: it vanishes once the user types.
        input.setAriaLabel(INPUT_NAME);
        input.getStyle().set("flex", "1 1 auto");
        input.addClassName("md-reply-input");
        LkTextLimit.apply(input);

        send.setIcon(new LkIcon("arrowRight", 15));
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(e -> submit());
        send.addClickShortcut(Key.ENTER);

        add(input, send);
    }

    /**
     * Sets what Send does.
     *
     * @param handler sends the reply and reports whether it went; replaces any earlier handler
     * @return this bar, for chaining
     */
    public MdReplyBar onSend(SendHandler handler) {
        this.onSend = handler;
        return this;
    }

    private void submit() {
        if (input.isEmpty() || onSend == null) return;
        if (!LkTextLimit.exceeds(input) && onSend.send(input.getValue())) {
            input.clear();
        }
        input.focus();
    }

    /** Sends a reply for {@link MdReplyBar}. */
    @FunctionalInterface
    public interface SendHandler {

        /**
         * Sends {@code text}, telling the user itself why when it cannot.
         *
         * @param text the reply as typed; not empty
         * @return {@code true} if the reply was sent, so the bar clears it; {@code false} to keep it
         */
        boolean send(String text);
    }
}
