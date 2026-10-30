package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.ui.components.messaging.MdReplyBar;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextField;

/**
 * {@link MdReplyBar} keeps what the user wrote until the reply is sent, names its input for
 * assistive technology, and flags a reply over the stored length instead of truncating it.
 * Constructed headless; {@link Button#click()} fires the same listener a user's click does.
 */
class MdReplyBarTest {

    private static final String REPLY = "Thanks, that solved it.";

    @Test
    void GivenASendThatFails_WhenTheUserSends_ThenTheReplyStaysInTheInput() {
        MdReplyBar bar = new MdReplyBar().onSend(text -> false);
        TextField input = inputOf(bar);
        input.setValue(REPLY);

        sendButtonOf(bar).click();

        assertEquals(REPLY, input.getValue());
    }

    @Test
    void GivenASendThatSucceeds_WhenTheUserSends_ThenTheInputIsCleared() {
        List<String> sent = new ArrayList<>();
        MdReplyBar bar = new MdReplyBar().onSend(text -> sent.add(text));
        TextField input = inputOf(bar);
        input.setValue(REPLY);

        sendButtonOf(bar).click();

        assertEquals(List.of(REPLY), sent);
        assertTrue(input.isEmpty());
    }

    @Test
    void GivenAReplyOverTheLimit_WhenTheUserSends_ThenNothingIsSentAndTheReplyStays() {
        List<String> sent = new ArrayList<>();
        MdReplyBar bar = new MdReplyBar().onSend(text -> sent.add(text));
        TextField input = inputOf(bar);
        String tooLong = "a".repeat(TextLimits.MAX_TEXT_LENGTH + 1);
        input.setValue(tooLong);

        sendButtonOf(bar).click();

        assertTrue(sent.isEmpty());
        assertEquals(tooLong, input.getValue());
        assertTrue(input.isInvalid());
    }

    @Test
    void GivenTheBar_WhenBuilt_ThenTheInputIsNamedReply() {
        MdReplyBar bar = new MdReplyBar();

        assertEquals(Optional.of("Reply"), inputOf(bar).getAriaLabel());
    }

    @Test
    void GivenTheBar_WhenBuilt_ThenTheInputNamesTheLimitAndCarriesNoMaxlength() {
        TextField input = inputOf(new MdReplyBar());

        assertEquals("Up to " + TextLimits.MAX_TEXT_LENGTH + " characters", input.getHelperText());
        assertFalse(input.getElement().hasProperty("maxlength"));
    }

    private static TextField inputOf(MdReplyBar bar) {
        return only(bar, TextField.class);
    }

    private static Button sendButtonOf(MdReplyBar bar) {
        return only(bar, Button.class);
    }

    private static <T extends Component> T only(Component root, Class<T> type) {
        List<T> found = root.getChildren().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), "expected one " + type.getSimpleName());
        return found.get(0);
    }
}
