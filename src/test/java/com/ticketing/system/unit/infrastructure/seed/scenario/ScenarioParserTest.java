package com.ticketing.system.unit.infrastructure.seed.scenario;

import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioCommand;
import com.ticketing.system.bootstrap.dev.seed.scenario.ScenarioParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioParserTest {

    private final ScenarioParser parser = new ScenarioParser();

    @Test
    void ignoresCommentsAndBlankLines() {
        List<ScenarioCommand> cmds = parser.parse("# a comment\n\n   # indented comment\nlogin u1\n");
        assertEquals(1, cmds.size());
        assertEquals("login", cmds.get(0).op());
        assertEquals("u1", cmds.get(0).pos(0));
    }

    @Test
    void parsesPositionalAndNamedArgs() {
        ScenarioCommand c = parser.parse("add-event u2 p1 e1 standing:30@50 publish=true days=20").get(0);
        assertEquals("add-event", c.op());
        assertEquals(List.of("u2", "p1", "e1", "standing:30@50"), c.positional());
        assertTrue(c.boolNamed("publish", false));
        assertEquals(20, c.intNamed("days", 1));
    }

    @Test
    void keepsQuotedPositionalValuesWithSpaces() {
        ScenarioCommand c = parser.parse("open-company u1 p1 \"Live Nation Israel\" \"Arena tours\"").get(0);
        assertEquals("Live Nation Israel", c.pos(2));
        assertEquals("Arena tours", c.pos(3));
    }

    @Test
    void keepsQuotedNamedValuesWithSpaces() {
        ScenarioCommand c = parser.parse(
            "contact-company carol live subject=\"Parking at the venue\" body=\"Is there parking?\"").get(0);
        assertEquals("Parking at the venue", c.named("subject"));
        assertEquals("Is there parking?", c.named("body"));
    }

    @Test
    void opIsLowercasedAndLineNumbersTracked() {
        ScenarioCommand c = parser.parse("# header line\nREGISTER u1 pw e@x.com 30").get(0);
        assertEquals("register", c.op());
        assertEquals(2, c.line());
    }

    @Test
    void requirePosThrowsLocatedErrorWhenMissing() {
        ScenarioCommand c = parser.parse("login").get(0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> c.requirePos(0, "user alias"));
        assertTrue(ex.getMessage().contains("user alias"));
    }
}
