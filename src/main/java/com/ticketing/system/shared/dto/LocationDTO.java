package com.ticketing.system.shared.dto;

/**
 * A venue's place, as a country and a city. {@link #toString()} renders it as
 * {@code "city, country"}.
 */
public record LocationDTO(
                String country,
                String city) {

        @Override
        public String toString() {
                return city + ", " + country;
        }
}

