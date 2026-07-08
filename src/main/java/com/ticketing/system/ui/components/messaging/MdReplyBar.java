package com.ticketing.system.ui.components.messaging;

import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;

import java.util.function.Consumer;

/**
 * Inline reply bar — real Vaadin {@link TextField} + primary Send
 * button. Calls back via {@link #onSend(Consumer)} with the text on
 * submit (Enter or button click); clears the field afterwards.
 */
public class MdReplyBar extends Div {

    private final TextField input = new TextField();
    private final Button    send  = new Button("Send");
    private Consumer<String> onSend;

    public MdReplyBar() {
        addClassName("md-reply");
        input.setPlaceholder("Write a reply…");
        input.getStyle().set("flex", "1 1 auto");
        input.addClassName("md-reply-input");

        send.setIcon(new LkIcon("arrowRight", 15));
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickListener(e -> submit());
        send.addClickShortcut(Key.ENTER);

        add(input, send);
    }

    public MdReplyBar onSend(Consumer<String> handler) {
        this.onSend = handler;
        return this;
    }

    private void submit() {
        if (input.isEmpty()) return;
        if (onSend != null) onSend.accept(input.getValue());
        input.clear();
        input.focus();
    }
}
