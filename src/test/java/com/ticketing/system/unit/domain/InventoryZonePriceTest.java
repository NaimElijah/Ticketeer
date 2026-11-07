package com.ticketing.system.unit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.StandingZone;

/**
 * #185: a zone's ticket price must be a finite number in {@code [0, MAX_ZONE_PRICE]}. The server is the
 * only check on it; the editor UI's field limits are advisory.
 *
 * <p>A zone refuses a price out of bounds through its invariant check, with an
 * {@link IllegalStateException}.
 */
class InventoryZonePriceTest {

    // InventoryZone.MAX_PRICE (OQ-28), written out so the bound itself is part of the specification.
    private static final double MAX_ZONE_PRICE = 1_000_000.0;
    private static final double JUST_OVER_MAX = 1_000_000.01;
    // The smallest step below the lower bound a price is entered in; -Infinity is refused as non-finite.
    private static final double JUST_UNDER_ZERO = -0.01;

    @ParameterizedTest(name = "price={0}")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, JUST_UNDER_ZERO,
            JUST_OVER_MAX})
    void GivenNonFiniteNegativeOrOverMaximumPrice_WhenCreateStandingZone_ThenRejected(double price) {
        assertThatThrownBy(() -> new StandingZone(1, "Floor", 10, price))
                .as("a standing zone priced %s must be refused", price)
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "price={0}")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, JUST_UNDER_ZERO,
            JUST_OVER_MAX})
    void GivenNonFiniteNegativeOrOverMaximumPrice_WhenCreateSeatedZone_ThenRejected(double price) {
        assertThatThrownBy(() -> new SeatedZone(1, "Orchestra", price, oneSeat()))
                .as("a seated zone priced %s must be refused", price)
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "price={0}")
    @ValueSource(doubles = {0.0, MAX_ZONE_PRICE})
    void GivenPriceAtEitherBound_WhenCreateStandingZone_ThenAccepted(double price) {
        StandingZone zone = new StandingZone(1, "Floor", 10, price);

        assertThat(zone.getprice()).isEqualTo(price);
    }

    @ParameterizedTest(name = "price={0}")
    @ValueSource(doubles = {0.0, MAX_ZONE_PRICE})
    void GivenPriceAtEitherBound_WhenCreateSeatedZone_ThenAccepted(double price) {
        SeatedZone zone = new SeatedZone(1, "Orchestra", price, oneSeat());

        assertThat(zone.getprice()).isEqualTo(price);
    }

    // A Seat is mutable (its status and hold), so each zone gets its own.
    private static List<Seat> oneSeat() {
        return List.of(new Seat("A1", 0, 0));
    }
}
