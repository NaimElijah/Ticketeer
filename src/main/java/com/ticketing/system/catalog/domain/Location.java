package com.ticketing.system.catalog.domain;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Embeddable;

// V3: an @Embeddable value object embedded in VenueMap (columns country, city). Hibernate 6
// instantiates the record via its canonical constructor; an all-null embedded location loads back
// as a null reference, so the validating compact constructor only runs for non-null values.
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

        @Override
        public String toString() {
                return city + ", " + country;
        }
}
