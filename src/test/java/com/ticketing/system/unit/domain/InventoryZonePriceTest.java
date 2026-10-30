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
 * <p>Rejection is asserted as either {@link IllegalArgumentException} or {@link IllegalStateException}:
 * today a negative price is refused by the zone's invariant check ({@code IllegalStateException}), and the
 * behaviour specified here is the refusal, not which of the two the constructor raises.
 */
class InventoryZonePriceTest {

    // The proposed named maximum (#185), mirrored here so the bound is part of the specification.
    private static final double MAX_ZONE_PRICE = 1_000_000.0;
    private static final double JUST_OVER_MAX = 1_000_000.01;

    private static final List<Seat> ONE_SEAT = List.of(new Seat("A1", 0, 0));

    @ParameterizedTest(name = "price={0}")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, JUST_OVER_MAX})
    void GivenNonFiniteOrOverMaximumPrice_WhenCreateStandingZone_ThenRejected(double price) {
        assertThatThrownBy(() -> new StandingZone(1, "Floor", 10, price))
                .as("a standing zone priced %s must be refused", price)
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @ParameterizedTest(name = "price={0}")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, JUST_OVER_MAX})
    void GivenNonFiniteOrOverMaximumPrice_WhenCreateSeatedZone_ThenRejected(double price) {
        assertThatThrownBy(() -> new SeatedZone(1, "Orchestra", price, ONE_SEAT))
                .as("a seated zone priced %s must be refused", price)
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
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
        SeatedZone zone = new SeatedZone(1, "Orchestra", price, ONE_SEAT);

        assertThat(zone.getprice()).isEqualTo(price);
    }
}
