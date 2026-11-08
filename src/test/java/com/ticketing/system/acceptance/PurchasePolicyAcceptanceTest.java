package com.ticketing.system.acceptance;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class PurchasePolicyAcceptanceTest {

    @Autowired private AuthenticationService   authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService  eventManagementService;
    @Autowired private ReservationService      reservationService;
    @Autowired private Clock clock;

    // unique counter so each @BeforeEach creates a fresh user + company
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private String token;
    private int    companyId;
    private int    eventId;

    private AuthTokenDTO registerAndLogin(String name) {
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 25));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }

    @BeforeEach
    void setUp() {
        int seq = SEQ.incrementAndGet();
        AuthTokenDTO auth = registerAndLogin("policyOwner" + seq);
        token = auth.token();

        companyId = companyService.registerCompany(
                token,
                new CompanyRegistrationDTO("PolicyCo" + seq, "desc")
        ).companyId();

        EventDetailDTO event = eventManagementService.addEvent(
                token,
                new EventCreationDTO(
                        companyId,
                        "Policy Test Event",
                        "Event for policy acceptance tests",
                        List.of("Test Artist"),
                        EventCategory.CONCERT,
                        0.0,
                        new Location("Israel", "Tel Aviv"),
                        List.of(new ShowDate(
                                LocalDateTime.now(clock).plusDays(30),
                                LocalDateTime.now(clock).plusDays(30).plusHours(3)
                        )),
                        ZoneId.of("Asia/Jerusalem"),
                        null
                ));
        eventId = Integer.parseInt(event.eventId());
    }

   
    @Test
    void GivenNewEvent_WhenGetEventPolicy_ThenReturnsNone() {
        PurchasePolicyDTO dto = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("NONE", dto.type());
    }

    // On the Memory adapters a read hands back the stored instance, so these show what the owner reads back, not
    // that a policy persists. JpaEventRepositoryContractTest and IProductionCompanyRepositoryContractTest read
    // policies back from the database.
    @Test
    void GivenAgePolicy_WhenSetEventPolicyAndGet_ThenTheOwnerReadsItBack() {
        PurchasePolicyDTO policy = new PurchasePolicyDTO("AGE", 18, null, null, null);
        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, policy));

        PurchasePolicyDTO loaded = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("AGE", loaded.type());
        assertEquals(18, loaded.minimumAge());
    }

    @Test
    void GivenMinTicketsPolicy_WhenSetEventPolicyAndGet_ThenTheOwnerReadsItBack() {
        PurchasePolicyDTO policy = new PurchasePolicyDTO("MIN_TICKETS", null, 2, null, null);
        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, policy));

        PurchasePolicyDTO loaded = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("MIN_TICKETS", loaded.type());
        assertEquals(2, loaded.minimumTickets());
    }

    @Test
    void GivenMaxTicketsPolicy_WhenSetEventPolicyAndGet_ThenTheOwnerReadsItBack() {
        PurchasePolicyDTO policy = new PurchasePolicyDTO("MAX_TICKETS", null, null, 5, null);
        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, policy));

        PurchasePolicyDTO loaded = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("MAX_TICKETS", loaded.type());
        assertEquals(5, loaded.maximumTickets());
    }

    @Test
    void GivenAndPolicy_WhenSetEventPolicyAndGet_ThenTreePreserved() {
        PurchasePolicyDTO age = new PurchasePolicyDTO("AGE", 18, null, null, null);
        PurchasePolicyDTO min = new PurchasePolicyDTO("MIN_TICKETS", null, 1, null, null);
        PurchasePolicyDTO and = new PurchasePolicyDTO("AND", null, null, null, List.of(age, min));

        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, and));

        PurchasePolicyDTO loaded = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("AND", loaded.type());
        assertEquals(2, loaded.children().size());
    }

    @Test
    void GivenOrPolicy_WhenSetEventPolicyAndGet_ThenTreePreserved() {
        PurchasePolicyDTO min = new PurchasePolicyDTO("MIN_TICKETS", null, 1, null, null);
        PurchasePolicyDTO max = new PurchasePolicyDTO("MAX_TICKETS", null, null, 4, null);
        PurchasePolicyDTO or  = new PurchasePolicyDTO("OR", null, null, null, List.of(min, max));

        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, or));

        PurchasePolicyDTO loaded = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("OR", loaded.type());
        assertEquals(2, loaded.children().size());
    }

    @Test
    void GivenPolicy_WhenOverwritten_ThenNewPolicyReturned() {
        PurchasePolicyDTO first  = new PurchasePolicyDTO("AGE", 18, null, null, null);
        PurchasePolicyDTO second = new PurchasePolicyDTO("MAX_TICKETS", null, null, 10, null);

        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, first));
        eventManagementService.setEventPolicies(token, new EventPolicyConfigDTO(companyId, eventId, second));

        PurchasePolicyDTO loaded = eventManagementService.getEventPurchasePolicy(token, companyId, eventId);
        assertEquals("MAX_TICKETS", loaded.type());
        assertEquals(10, loaded.maximumTickets());
    }

    // A string that is no JWT fails parsing, so the refusal names the failed validation.
    @Test
    void GivenInvalidToken_WhenGetEventPolicy_ThenThrows() {
        InvalidTokenException refused = assertThrowsExactly(InvalidTokenException.class, () ->
                eventManagementService.getEventPurchasePolicy("bad-token", companyId, eventId));

        assertEquals("Invalid authentication token: token validation failed", refused.getMessage());
    }

    // The company is looked up before membership or the event, so a company that does not exist is refused
    // as such. EventManagementServiceTest covers the refusal of another company's event.
    @Test
    void GivenUnknownCompanyId_WhenGetEventPolicy_ThenCompanyNotFound() {
        int unknownCompanyId = companyId + 9999;

        CompanyNotFoundException refused = assertThrowsExactly(CompanyNotFoundException.class, () ->
                eventManagementService.getEventPurchasePolicy(token, unknownCompanyId, eventId));

        assertEquals("ProductionCompany not found: " + unknownCompanyId, refused.getMessage());
    }


    @Test
    void GivenNewCompany_WhenGetCompanyPolicy_ThenReturnsNone() {
        PurchasePolicyDTO dto = companyService.getCompanyPurchasePolicy(token, companyId);
        assertEquals("NONE", dto.type());
    }

    @Test
    void GivenAgePolicy_WhenSetCompanyPolicyAndGet_ThenTheOwnerReadsItBack() {
        PurchasePolicyDTO policy = new PurchasePolicyDTO("AGE", 21, null, null, null);
        companyService.setCompanyPolicies(token, new CompanyPolicyConfigDTO(companyId, policy, List.of()));

        PurchasePolicyDTO loaded = companyService.getCompanyPurchasePolicy(token, companyId);
        assertEquals("AGE", loaded.type());
        assertEquals(21, loaded.minimumAge());
    }

    @Test
    void GivenAndPolicy_WhenSetCompanyPolicyAndGet_ThenTreePreserved() {
        PurchasePolicyDTO age = new PurchasePolicyDTO("AGE", 18, null, null, null);
        PurchasePolicyDTO max = new PurchasePolicyDTO("MAX_TICKETS", null, null, 6, null);
        PurchasePolicyDTO and = new PurchasePolicyDTO("AND", null, null, null, List.of(age, max));

        companyService.setCompanyPolicies(token, new CompanyPolicyConfigDTO(companyId, and, List.of()));

        PurchasePolicyDTO loaded = companyService.getCompanyPurchasePolicy(token, companyId);
        assertEquals("AND", loaded.type());
        assertEquals(2, loaded.children().size());
    }

    @Test
    void GivenInvalidToken_WhenGetCompanyPolicy_ThenThrows() {
        InvalidTokenException refused = assertThrowsExactly(InvalidTokenException.class, () ->
                companyService.getCompanyPurchasePolicy("bad-token", companyId));

        assertEquals("Invalid authentication token: token validation failed", refused.getMessage());
    }

    // Reading a company's policy needs EDIT_POLICIES, which a member outside the company does not hold.
    @Test
    void GivenNonOwner_WhenGetCompanyPolicy_ThenThrows() {
        int seq = SEQ.incrementAndGet();
        AuthTokenDTO other = registerAndLogin("nonOwner" + seq);
        UnauthorizedActionException refused = assertThrowsExactly(UnauthorizedActionException.class, () ->
                companyService.getCompanyPurchasePolicy(other.token(), companyId));

        assertEquals("Not authorized to perform action: Missing permission: EDIT_POLICIES", refused.getMessage());
    }

    @Test
    void GivenMemberWithNoCart_WhenViewMyActiveOrder_ThenNull() {
        ActiveOrderDTO result = reservationService.viewMyActiveOrder(token);
        assertNull(result);
    }

    @Test
    void GivenGuestWithNoCart_WhenViewMyActiveOrder_ThenNull() {
        String guestSid = authService.startGuestSession().sessionId();
        ActiveOrderDTO result = reservationService.viewMyActiveOrder(guestSid);
        assertNull(result);
    }

    @Test
    void GivenNullInput_WhenViewMyActiveOrder_ThenNull() {
        assertNull(reservationService.viewMyActiveOrder(null));
    }

    @Test
    void GivenBlankInput_WhenViewMyActiveOrder_ThenNull() {
        assertNull(reservationService.viewMyActiveOrder("   "));
    }
}
