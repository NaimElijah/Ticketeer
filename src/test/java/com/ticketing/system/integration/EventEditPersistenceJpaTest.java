package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;

import jakarta.persistence.EntityManager;

/**
 * An owner's edit of an event's details persists (UC-19). {@code CompanyAcceptanceTest}'s edit tests run on
 * the Memory adapters, which hand back the stored instance, so they would pass with the edit never written.
 * Here the event is read back on the Jpa adapter, from a cleared persistence context, so every value read
 * comes from the database.
 */
@SpringBootTest
@ActiveProfiles("test")
class EventEditPersistenceJpaTest {

    private static final ZoneId CREATED_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final ZoneId EDITED_ZONE = ZoneId.of("Europe/Paris");
    private static final int DAYS_UNTIL_SHOW = 10;
    private static final int SHOW_HOURS = 3;
    private static final String PASSWORD = "Password1";
    private static final int MEMBER_AGE = 30;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    @Test
    void GivenOwner_WhenEditEventDetails_ThenChangesSurviveAReload() {
        AuthTokenDTO owner = registerAndLogin("editPersistOwner");
        int companyId = companyService.registerCompany(owner.token(),
                new CompanyRegistrationDTO("EditPersistCo-" + owner.userId(), "desc")).companyId();
        // Whole minutes, so the database's timestamp precision cannot tell the read value from the written one.
        LocalDateTime showStart = LocalDateTime.now(clock.withZone(CREATED_ZONE))
                .plusDays(DAYS_UNTIL_SHOW).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime showEnd = showStart.plusHours(SHOW_HOURS);
        int eventId = Integer.parseInt(eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Before Edit", "original desc", List.of("Artist"), EventCategory.MUSIC, 4.0,
                new Location("Italy", "Rome"),
                List.of(new ShowDate(showStart, showEnd)),
                CREATED_ZONE,
                null)).eventId());

        eventManagementService.editEventDetails(owner.token(), new EventUpdateDTO(
                String.valueOf(eventId), "After Edit", "updated desc", "THEATER",
                new LocationDTO("France", "Paris"),
                EDITED_ZONE,
                null));

        assertEquals(new StoredDetails("After Edit", "updated desc", EventCategory.THEATER,
                new Location("France", "Paris"), EDITED_ZONE, List.of(List.of(showStart, showEnd))), reloaded(eventId),
                "every edited field, and the schedule left alone, must be read back from the database");
    }

    private record StoredDetails(String name, String description, EventCategory category, Location location,
            ZoneId timeZone, List<List<LocalDateTime>> showDates) { }

    private StoredDetails reloaded(int eventId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            entityManager.clear();
            Event event = eventRepository.findById(eventId);
            return new StoredDetails(event.getName(), event.getDescription(), event.getCategory(),
                    event.getVenueMap().getLocation(), event.getTimeZone(),
                    event.getShowDates().stream()
                            .map(showDate -> List.of(showDate.getStartTime(), showDate.getEndTime()))
                            .toList());
        });
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + "-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionId = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", PASSWORD, sessionId, MEMBER_AGE));
        return authService.login(new LoginRequestDTO(name, PASSWORD, sessionId)).authToken();
    }
}
