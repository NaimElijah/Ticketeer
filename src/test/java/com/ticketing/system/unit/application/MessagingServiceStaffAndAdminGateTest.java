package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.identity.adapter.out.persistence.MemoryAdminRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.messaging.adapter.out.persistence.MemoryConversationRepository;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.dto.ComplaintFilterDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.dto.RespondToComplaintRequestDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.shared.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.shared.event.NewMessageNotice;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.InvalidParticipantException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

/**
 * {@link MessagingService} on real in-memory adapters and the real {@link CompanyMembershipService}: who may
 * act for a company (II.4.4), the admin gate's check that the admin still exists, and the failure paths
 * and orderings #189 found untested. Two doubles stand in: the session port, and the event publisher, a mock
 * so a test can read the notices the service published.
 */
class MessagingServiceStaffAndAdminGateTest {

    private static final Instant DAY_ONE = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDate DAY_ONE_DATE = LocalDate.of(2027, 7, 15);
    private static final Duration ONE_DAY = Duration.ofDays(1);
    private static final int MEMBER_AGE = 30;
    private static final int COMPANY_ID = 5;
    private static final int UNKNOWN_COMPANY_ID = 404;

    private static final String MEMBER_TOKEN = "member-tok";
    private static final int MEMBER_ID = 10;
    private static final String MEMBER2_TOKEN = "member2-tok";
    private static final int MEMBER2_ID = 11;
    private static final String OWNER_TOKEN = "owner-tok";
    private static final int OWNER_ID = 20;
    // A manager who may answer the company's inquiries, and one who may not.
    private static final String RESPONDER_TOKEN = "responder-tok";
    private static final int RESPONDER_ID = 31;
    private static final String NON_RESPONDER_TOKEN = "non-responder-tok";
    private static final int NON_RESPONDER_ID = 32;
    private static final String OUTSIDER_TOKEN = "outsider-tok";
    private static final int OUTSIDER_ID = 99;
    private static final String ADMIN_TOKEN = "admin-tok";
    private static final int ADMIN_ID = 1;
    // A live admin token whose admin has since been removed from the admin pool.
    private static final String REMOVED_ADMIN_TOKEN = "removed-admin-tok";
    private static final int REMOVED_ADMIN_ID = 3;
    private static final String REMOVED_ADMIN_REFUSAL =
            "Actor " + REMOVED_ADMIN_ID + " not authorized to perform action: messaging admin operation";

    private final SessionManager sessions = mock(SessionManager.class);
    private final MemoryConversationRepository conversations = new MemoryConversationRepository();
    private final MemoryAdminRepository admins = new MemoryAdminRepository();
    private final MemoryUserRepository users = new MemoryUserRepository();
    private final MemoryProductionCompanyRepository companies = new MemoryProductionCompanyRepository();
    private final CompanyMembershipService membership =
            new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), Clock.fixed(DAY_ONE, ZoneOffset.UTC));
    // One publisher for every service a test builds, so published() reads what each of them published.
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private MessagingService service;

    @BeforeEach
    void setUp() {
        for (int userId : List.of(MEMBER_ID, MEMBER2_ID, OWNER_ID, RESPONDER_ID, NON_RESPONDER_ID, OUTSIDER_ID)) {
            users.save(new User(userId, "user" + userId, "user" + userId + "@example.test", "unused-password-hash",
                    MEMBER_AGE));
        }
        memberToken(MEMBER_TOKEN, MEMBER_ID);
        memberToken(MEMBER2_TOKEN, MEMBER2_ID);
        memberToken(OWNER_TOKEN, OWNER_ID);
        memberToken(RESPONDER_TOKEN, RESPONDER_ID);
        memberToken(NON_RESPONDER_TOKEN, NON_RESPONDER_ID);
        memberToken(OUTSIDER_TOKEN, OUTSIDER_ID);
        admins.save(new Admin(ADMIN_ID, "admin", "unused-password-hash", true));
        adminToken(ADMIN_TOKEN, ADMIN_ID);
        adminToken(REMOVED_ADMIN_TOKEN, REMOVED_ADMIN_ID);

        companies.save(new ProductionCompany(COMPANY_ID, OWNER_ID, "Acme Live", CompanyStatus.ACTIVE, null, null));
        membership.addFounderAppointment(OWNER_ID, COMPANY_ID);
        appointManager(RESPONDER_ID, Permission.RESPOND_TO_INQUIRIES);
        appointManager(NON_RESPONDER_ID, Permission.VIEW_SALES);

        service = serviceAt(DAY_ONE);
    }

    private MessagingService serviceAt(Instant now) {
        return new MessagingService(conversations, sessions, admins, users, companies, membership, events,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void appointManager(int userId, Permission permission) {
        membership.receiveManagerAppointment(userId, COMPANY_ID, OWNER_ID, List.of(permission));
        membership.acceptInvitation(userId, COMPANY_ID);
    }

    private void memberToken(String token, int userId) {
        when(sessions.validateToken(token)).thenReturn(true);
        when(sessions.extractUserId(token)).thenReturn(userId);
    }

    private void adminToken(String token, int adminId) {
        when(sessions.isAdminToken(token)).thenReturn(true);
        when(sessions.validateAdminToken(token)).thenReturn(true);
        when(sessions.extractAdminId(token)).thenReturn(adminId);
    }

    private ConversationDTO inquiryFrom(String memberToken) {
        return service.startConversation(memberToken, new StartConversationRequestDTO(COMPANY_ID, "Parking?",
                "Is there parking at the venue?"));
    }

    private String complaintFrom(String memberToken) {
        return service.submitComplaint(memberToken,
                new SubmitComplaintRequestDTO(0, "Refund late", "My refund has not arrived.", null)).conversationId();
    }

    private List<Object> published() {
        return mockingDetails(events).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("publishEvent"))
                .map(invocation -> invocation.getArguments()[0])
                .toList();
    }

    private static List<String> idsOf(List<ConversationDTO> list) {
        return list.stream().map(ConversationDTO::conversationId).toList();
    }

    // ---- II.4.4: a manager holding RESPOND_TO_INQUIRIES acts for the company ----

    @Test
    void GivenManagerWithRespondToInquiries_WhenViewCompanyInbox_ThenTheCompanysInquiriesSeenByTheCompany() {
        ConversationDTO inquiry = inquiryFrom(MEMBER_TOKEN);

        List<ConversationDTO> inbox = service.viewCompanyInbox(RESPONDER_TOKEN, COMPANY_ID);

        assertEquals(List.of(inquiry.conversationId()), idsOf(inbox));
        assertEquals(1, inbox.get(0).unreadCountForViewer(), "the member's question is unread for the company");
    }

    @Test
    void GivenManagerWithoutRespondToInquiries_WhenViewCompanyInbox_ThenUnauthorized() {
        inquiryFrom(MEMBER_TOKEN);

        UnauthorizedActionException refused = assertThrowsExactly(UnauthorizedActionException.class,
                () -> service.viewCompanyInbox(NON_RESPONDER_TOKEN, COMPANY_ID));

        assertEquals("Actor " + NON_RESPONDER_ID + " not authorized to perform action: view company inbox",
                refused.getMessage());
    }

    // ---- the messaging admin gate also checks that the token's admin still exists ----

    @Test
    void GivenAdminTokenWhoseAdminNoLongerExists_WhenSendOutreach_ThenUnauthorizedAndNothingSent() {
        UnauthorizedActionException refused = assertThrowsExactly(UnauthorizedActionException.class,
                () -> service.sendOutreach(REMOVED_ADMIN_TOKEN,
                        new OutreachRequestDTO("Venue change", "Hall B.", null, true, false)));

        assertEquals(REMOVED_ADMIN_REFUSAL, refused.getMessage());
        assertEquals(List.of(), conversations.findByType(ConversationType.DIRECT));
        assertEquals(List.of(), published());
    }

    @Test
    void GivenAdminTokenWhoseAdminNoLongerExists_WhenRespondToComplaint_ThenUnauthorizedAndTheComplaintUnanswered() {
        String complaint = complaintFrom(MEMBER_TOKEN);

        UnauthorizedActionException refused = assertThrowsExactly(UnauthorizedActionException.class,
                () -> service.respondToComplaint(REMOVED_ADMIN_TOKEN,
                        new RespondToComplaintRequestDTO(complaint, REMOVED_ADMIN_ID, "Done.", "RESOLVED")));

        assertEquals(REMOVED_ADMIN_REFUSAL, refused.getMessage());
        Conversation stored = conversations.findById(complaint).orElseThrow();
        assertEquals(1, stored.getMessages().size());
        assertEquals(ConversationStatus.OPEN, stored.getStatus());
        assertEquals(List.of(), published());
    }

    static Stream<Arguments> adminReads() {
        return Stream.of(
                Arguments.of("viewAllComplaints",
                        (Consumer<MessagingService>) s -> s.viewAllComplaints(REMOVED_ADMIN_TOKEN, null)),
                Arguments.of("viewSentOutreach",
                        (Consumer<MessagingService>) s -> s.viewSentOutreach(REMOVED_ADMIN_TOKEN)),
                Arguments.of("viewAdminInbox",
                        (Consumer<MessagingService>) s -> s.viewAdminInbox(REMOVED_ADMIN_TOKEN)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminReads")
    void GivenAdminTokenWhoseAdminNoLongerExists_WhenAnAdminReadIsCalled_ThenUnauthorized(String read,
            Consumer<MessagingService> call) {
        complaintFrom(MEMBER_TOKEN);

        UnauthorizedActionException refused = assertThrowsExactly(UnauthorizedActionException.class,
                () -> call.accept(service));

        assertEquals(REMOVED_ADMIN_REFUSAL, refused.getMessage());
    }

    // ---- other failure paths ----

    @Test
    void GivenAnInquiry_WhenRespondToComplaintOnIt_ThenRefusedAsNotAComplaintAndUnchanged() {
        ConversationDTO inquiry = inquiryFrom(MEMBER_TOKEN);

        BusinessRuleViolationException refused = assertThrowsExactly(BusinessRuleViolationException.class,
                () -> service.respondToComplaint(ADMIN_TOKEN,
                        new RespondToComplaintRequestDTO(inquiry.conversationId(), ADMIN_ID, "Done.", "RESOLVED")));

        assertEquals("Conversation " + inquiry.conversationId() + " is not a complaint", refused.getMessage());
        Conversation stored = conversations.findById(inquiry.conversationId()).orElseThrow();
        assertEquals(1, stored.getMessages().size());
        assertEquals(ConversationStatus.OPEN, stored.getStatus());
    }

    @Test
    void GivenAnInquiry_WhenAnOutsiderClosesIt_ThenRefusedAndItStaysOpen() {
        ConversationDTO inquiry = inquiryFrom(MEMBER_TOKEN);

        assertThrowsExactly(InvalidParticipantException.class,
                () -> service.closeConversation(OUTSIDER_TOKEN, inquiry.conversationId()));

        assertEquals(ConversationStatus.OPEN, conversations.findById(inquiry.conversationId()).orElseThrow().getStatus());
    }

    @Test
    void GivenAnUnknownCompany_WhenStartConversation_ThenCompanyNotFoundAndNothingStoredOrPublished() {
        assertThrowsExactly(CompanyNotFoundException.class, () -> service.startConversation(MEMBER_TOKEN,
                new StartConversationRequestDTO(UNKNOWN_COMPANY_ID, "Hello?", "Anyone there?")));

        assertEquals(List.of(), conversations.findByType(ConversationType.INQUIRY));
        assertEquals(List.of(), published());
    }

    // A notice carries a preview of at most 120 characters, then an ellipsis.
    @Test
    void GivenRepliesOf120And121Characters_WhenSent_ThenOnlyTheLongerPreviewIsCutWithAnEllipsis() {
        ConversationDTO inquiry = inquiryFrom(MEMBER_TOKEN);
        String fits = "a".repeat(120);
        String overflows = "b".repeat(121);
        clearInvocations(events);

        service.sendMessage(OWNER_TOKEN, new SendMessageRequestDTO(inquiry.conversationId(), 0, null, fits));
        service.sendMessage(OWNER_TOKEN, new SendMessageRequestDTO(inquiry.conversationId(), 0, null, overflows));

        assertEquals(List.of(fits, "b".repeat(120) + "…"), published().stream()
                .map(event -> ((NewMessageNotice) event).snippet()).toList());
    }

    @Test
    void GivenConversationsStartedOnDifferentDays_WhenViewMyConversations_ThenNewestFirst() {
        String older = inquiryFrom(MEMBER_TOKEN).conversationId();
        service = serviceAt(DAY_ONE.plus(ONE_DAY));
        String newer = complaintFrom(MEMBER_TOKEN);

        assertEquals(List.of(newer, older), idsOf(service.viewMyConversations(MEMBER_TOKEN)));
    }

    // fromDate and toDate are both inclusive days of the complaint's creation.
    @Test
    void GivenComplaintsOnThreeDays_WhenViewAllComplaintsFromTheSecondDayToTheThird_ThenTheLaterTwoNewestFirst() {
        complaintFrom(MEMBER_TOKEN);
        service = serviceAt(DAY_ONE.plus(ONE_DAY));
        String second = complaintFrom(MEMBER2_TOKEN);
        service = serviceAt(DAY_ONE.plus(ONE_DAY.multipliedBy(2)));
        String third = complaintFrom(MEMBER_TOKEN);

        List<ConversationDTO> found = service.viewAllComplaints(ADMIN_TOKEN,
                new ComplaintFilterDTO(null, null, DAY_ONE_DATE.plusDays(1), DAY_ONE_DATE.plusDays(2)));

        assertEquals(List.of(third, second), idsOf(found));
    }

    @Test
    void GivenComplaintsOnThreeDays_WhenViewAllComplaintsForOneDay_ThenOnlyThatDays() {
        complaintFrom(MEMBER_TOKEN);
        service = serviceAt(DAY_ONE.plus(ONE_DAY));
        String second = complaintFrom(MEMBER2_TOKEN);
        service = serviceAt(DAY_ONE.plus(ONE_DAY.multipliedBy(2)));
        complaintFrom(MEMBER_TOKEN);

        List<ConversationDTO> found = service.viewAllComplaints(ADMIN_TOKEN,
                new ComplaintFilterDTO(null, null, DAY_ONE_DATE.plusDays(1), DAY_ONE_DATE.plusDays(1)));

        assertEquals(List.of(second), idsOf(found));
    }

    // The owner is both a member and a producer, so a send to both groups reaches them once.
    @Test
    void GivenAnOwnerWhoIsAlsoAMember_WhenSendOutreachToAllMembersAndProducers_ThenOneConversationEach() {
        service.sendOutreach(ADMIN_TOKEN, new OutreachRequestDTO("Venue change", "Hall B.", null, true, true));

        List<Integer> recipients = conversations.findByType(ConversationType.DIRECT).stream()
                .map(Conversation::getCounterpartyId).sorted().toList();
        assertEquals(List.of(MEMBER_ID, MEMBER2_ID, OWNER_ID, RESPONDER_ID, NON_RESPONDER_ID, OUTSIDER_ID), recipients);
    }
}
