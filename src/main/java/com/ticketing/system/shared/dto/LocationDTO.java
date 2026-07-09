package com.ticketing.system.shared.dto;

public record LocationDTO(
                String country,
                String city) {

        @Override
        public String toString() {
                return city + ", " + country;
        }
}

