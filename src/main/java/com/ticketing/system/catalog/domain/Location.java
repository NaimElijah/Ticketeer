package com.ticketing.system.catalog.domain;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Embeddable;

/**
 * A venue's place, as country and city: an {@code @Embeddable} value object embedded in
 * {@link VenueMap} (columns {@code country} and {@code city}). Constructing one with a null or blank
 * country or city throws {@code IllegalStateException}. Hibernate 6 instantiates the record through
 * its canonical constructor; an all-null embedded location loads back as a {@code null} reference, so
 * the validating compact constructor only runs for non-null values.
 *
 * @param country the country; non-blank
 * @param city    the city; non-blank
 */
@Embeddable
public record Location(
                String country,
                String city) implements InvariantChecked {

        // Compact constructor validates the canonical components before they are assigned;
        // checkInvariants() restates the same rules for the InvariantChecked contract.
        public Location {
                if (country == null || country.isBlank()) {
                        throw new IllegalStateException("Location invariant violated: country must be non-blank");
                }
                if (city == null || city.isBlank()) {
                        throw new IllegalStateException("Location invariant violated: city must be non-blank");
                }
        }

        @Override
        public void checkInvariants() {
                if (country == null || country.isBlank()) {
                        throw new IllegalStateException("Location invariant violated: country must be non-blank");
                }
                if (city == null || city.isBlank()) {
                        throw new IllegalStateException("Location invariant violated: city must be non-blank");
                }
        }

        /** @return the location as {@code "city, country"} */
        @Override
        public String toString() {
                return city + ", " + country;
        }
}
