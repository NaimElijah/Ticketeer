package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;

// The aggregates' own tests cover which fields are bounded; these pin how the rule counts and what it says.
class TextLimitsTest {

    private static final String EMOJI = "😀";

    // LoggingAspect logs a refused call's exception message at WARN, so the text the user typed must not
    // be in it.
    @Test
    void GivenTextOverTheLimit_WhenChecked_ThenTheMessageNamesTheFieldAndLimitButNotTheText() {
        String typed = "secret-" + "z".repeat(TextLimits.MAX_TEXT_LENGTH);

        BusinessRuleViolationException refused = assertThrows(BusinessRuleViolationException.class,
                () -> TextLimits.requireFits("Message", typed));

        assertEquals("Message can be at most 255 characters.", refused.getMessage());
        assertFalse(refused.getMessage().contains("secret-"));
    }

    // H2 counts a varchar's length in UTF-16 units, so the rule does too: an emoji counts as two. Counting
    // characters, as PostgreSQL does, would accept text that H2 then refuses on insert.
    @Test
    void GivenEmojiText_WhenChecked_ThenEachEmojiCountsAsTwo() {
        String fits = EMOJI.repeat(127) + "a";
        String tooLong = EMOJI.repeat(128);

        assertDoesNotThrow(() -> TextLimits.requireFits("Message", fits));
        assertThrows(BusinessRuleViolationException.class, () -> TextLimits.requireFits("Message", tooLong));
    }
}
