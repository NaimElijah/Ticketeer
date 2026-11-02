package com.ticketing.system.unit.domain;

import static com.ticketing.system.sales.domain.TicketStatus.AVAILABLE;
import static com.ticketing.system.sales.domain.TicketStatus.ISSUED;
import static com.ticketing.system.sales.domain.TicketStatus.PAID;
import static com.ticketing.system.sales.domain.TicketStatus.REFUNDED;
import static com.ticketing.system.sales.domain.TicketStatus.RESERVED;
import static com.ticketing.system.sales.domain.TicketStatus.USED;
import static com.ticketing.system.sales.domain.TicketStatus.VOIDED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.ticketing.system.sales.domain.TicketStatus;

/**
 * Pins {@link TicketStatus#canBecome} over every one of the 49 pairs of states, so a transition can be
 * neither added nor dropped without this table changing too (#187). The table is the one TicketStatus's
 * Javadoc lists: any state may become VOIDED, and these others are legal.
 */
class TicketStatusTest {

    private static final Map<TicketStatus, Set<TicketStatus>> LEGAL_BESIDES_VOIDED = Map.of(
            AVAILABLE, Set.of(RESERVED),
            RESERVED, Set.of(PAID, AVAILABLE),
            PAID, Set.of(ISSUED, REFUNDED),
            ISSUED, Set.of(USED, REFUNDED),
            USED, Set.of(),
            REFUNDED, Set.of(),
            VOIDED, Set.of());

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPairOfStates")
    void GivenAPairOfStates_WhenCanBecome_ThenLegalExactlyWhenTheTableListsTheTransition(
            TicketStatus from, TicketStatus to) {
        boolean listed = to == VOIDED || LEGAL_BESIDES_VOIDED.get(from).contains(to);

        assertEquals(listed, from.canBecome(to),
                from + " -> " + to + (listed ? " is listed, so it must be legal" : " is not listed, so it must be refused"));
    }

    private static Stream<Arguments> everyPairOfStates() {
        return Arrays.stream(TicketStatus.values())
                .flatMap(from -> Arrays.stream(TicketStatus.values()).map(to -> Arguments.of(from, to)));
    }
}
