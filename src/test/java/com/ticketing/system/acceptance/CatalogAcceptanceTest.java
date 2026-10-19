package com.ticketing.system.acceptance;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.persistence.PersistenceProfiles;

@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class CatalogAcceptanceTest {

    // UC-3
    @Test @Disabled("UC-3 main: Guest browses public events from active companies")
    void GivenActiveCompanies_WhenBrowse_ThenSeeActiveEvents() {}
    @Test @Disabled("UC-3 negative: events from closed companies excluded")
    void GivenClosedCompany_WhenBrowse_ThenItsEventsExcluded() {}

    // UC-7
    @Test @Disabled("UC-7 main: global search by name returns matches")
    void GivenSearchByName_WhenSearchGlobal_ThenMatches() {}
    @Test @Disabled("UC-7 main: filter by date range works")
    void GivenDateRange_WhenSearchGlobal_ThenWithinRange() {}
    @Test @Disabled("UC-7 alt: company-scoped search excludes rating filter (II.2.3.2)")
    void GivenRatingFilter_WhenSearchByCompany_ThenIgnored() {}

    // UC-8
    @Test @Disabled("UC-8 main: venue map shows seated zones with per-seat status")
    void GivenSeatedZone_WhenGetMap_ThenPerSeatAvailability() {
        // Arrange:
        // event has SeatedZone:
        // A1 AVAILABLE
        // A2 RESERVED
        // A3 SOLD

        // Act:
        // VenueMapDTO map = catalogService.getEventVenueMap(credential, eventId);

        // Assert:
        // map contains zoneType SEATED
        // map contains all seat labels
        // A1 status AVAILABLE
        // A2 status RESERVED
        // A3 status SOLD
    }

    @Test @Disabled("UC-8 main: standing zone shows count")
    void GivenStandingZone_WhenGetMap_ThenCount() {
        // Arrange:
        // event has StandingZone capacity 100, reserved 25

        // Act:
        // VenueMapDTO map = catalogService.getEventVenueMap(credential, eventId);

        // Assert:
        // zoneType STANDING
        // capacity = 100
        // reservedAmount = 25
        // availableAmount = 75
        // seats list is empty
    }
    



}
