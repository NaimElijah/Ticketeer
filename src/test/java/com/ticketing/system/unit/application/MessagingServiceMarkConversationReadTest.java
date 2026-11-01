package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.messaging.adapter.out.persistence.MemoryConversationRepository;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.RespondToComplaintRequestDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.shared.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.shared.exception.InvalidParticipantException;

// ---- Nothing ever marks a conversation read (#187) ----
//
// Opening a thread marks every message from the other side read for the caller's side, so the unread
// badge falls back to 0. Admins answer the complaint queue as one team: for an admin reader, a message
// from any admin is the reader's own side, both when marking and when counting.
class MessagingServiceMarkConversationReadTest {

    private static final String MEMBER_TOKEN = "member-tok";
    private static final int MEMBER_ID = 10;
    private static final String OWNER_TOKEN = "owner-tok";
    private static final int OWNER_ID = 20;
    private static final String OUTSIDER_TOKEN = "outsider-tok";
    private static final int OUTSIDER_ID = 99;
    private static final int COMPANY_ID = 5;
    private static final String ADMIN_A_TOKEN = "admin-a-tok";
    private static final int ADMIN_A_ID = 1;
    private static final String ADMIN_B_TOKEN = "admin-b-tok";
    private static final int ADMIN_B_ID = 2;
    private static final int MEMBER_AGE = 30;
    private static final Instant NOW = Instant.parse("2027-07-15T17:00:00Z");

    private MessagingService service;

    @BeforeEach
    void setUp() {
        SessionManager sessions = mock(SessionManager.class);
        memberToken(sessions, MEMBER_TOKEN, MEMBER_ID);
        memberToken(sessions, OWNER_TOKEN, OWNER_ID);
        memberToken(sessions, OUTSIDER_TOKEN, OUTSIDER_ID);
        AdminRepository admins = mock(AdminRepository.class);
        adminToken(sessions, admins, ADMIN_A_TOKEN, ADMIN_A_ID);
        adminToken(sessions, admins, ADMIN_B_TOKEN, ADMIN_B_ID);

        MemoryUserRepository users = new MemoryUserRepository();
        users.save(new User(MEMBER_ID, "member", "member@example.test", "unused-password-hash", MEMBER_AGE));
        users.save(new User(OWNER_ID, "owner", "owner@example.test", "unused-password-hash", MEMBER_AGE));
        users.save(new User(OUTSIDER_ID, "outsider", "outsider@example.test", "unused-password-hash", MEMBER_AGE));
        MemoryProductionCompanyRepository companies = new MemoryProductionCompanyRepository();
        companies.save(new ProductionCompany(COMPANY_ID, OWNER_ID, "Acme Live", CompanyStatus.ACTIVE, null, null));
        CompanyMembershipService membership = mock(CompanyMembershipService.class);
        when(membership.isOwnerInCompany(OWNER_ID, COMPANY_ID)).thenReturn(true);

        service = new MessagingService(new MemoryConversationRepository(), sessions, admins, users, companies,
                membership, mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void GivenAnInquiryWithUnreadReplies_WhenTheMemberMarksItRead_ThenTheMemberHasNothingUnreadAndTheCompanysCountIsUnchanged() {
        String inquiry = inquiryWithTwoCompanyReplies();

        service.markConversationRead(MEMBER_TOKEN, inquiry);

        assertEquals(0, service.viewConversation(MEMBER_TOKEN, inquiry).unreadCountForViewer(),
                "both company replies are read for the member");
        assertEquals(1, companyUnread(inquiry), "the member's opening message is still unread for the company");
    }

    @Test
    void GivenASupportThreadAnsweredByAdminA_WhenAdminBViewsIt_ThenAdminAsReplyIsNotUnreadForB() {
        String complaint = complaintAnsweredByAdminA();

        ConversationDTO seenByB = service.viewConversation(ADMIN_B_TOKEN, complaint);

        assertEquals(1, seenByB.unreadCountForViewer(), "only the member's message is unread for admin B");
    }

    @Test
    void GivenASupportThreadAnsweredByAdminA_WhenAdminBMarksItRead_ThenNothingIsUnreadForBAndTheMemberStillHasTheReplyUnread() {
        String complaint = complaintAnsweredByAdminA();

        service.markConversationRead(ADMIN_B_TOKEN, complaint);

        assertEquals(0, service.viewConversation(ADMIN_B_TOKEN, complaint).unreadCountForViewer(),
                "the member's message is read for the admins");
        assertEquals(1, service.viewConversation(MEMBER_TOKEN, complaint).unreadCountForViewer(),
                "admin A's reply is the admins' own message: admin B reading the thread does not read it for the member");
    }

    @Test
    void GivenAnOutsider_WhenTheyMarkAConversationRead_ThenRefusedAndNothingChanges() {
        String inquiry = inquiryWithTwoCompanyReplies();

        assertThrows(InvalidParticipantException.class, () -> service.markConversationRead(OUTSIDER_TOKEN, inquiry));

        assertEquals(2, service.viewConversation(MEMBER_TOKEN, inquiry).unreadCountForViewer());
        assertEquals(1, companyUnread(inquiry));
    }

    private String inquiryWithTwoCompanyReplies() {
        String id = service.startConversation(MEMBER_TOKEN,
                new StartConversationRequestDTO(COMPANY_ID, "Parking?", "Is there parking near the venue?"))
                .conversationId();
        service.sendMessage(OWNER_TOKEN, new SendMessageRequestDTO(id, 0, null, "Yes, a paid lot."));
        service.sendMessage(OWNER_TOKEN, new SendMessageRequestDTO(id, 0, null, "It opens at six."));
        return id;
    }

    private String complaintAnsweredByAdminA() {
        String id = service.submitComplaint(MEMBER_TOKEN,
                new SubmitComplaintRequestDTO(MEMBER_ID, "Refund", "Where is my refund?", null)).conversationId();
        service.respondToComplaint(ADMIN_A_TOKEN,
                new RespondToComplaintRequestDTO(id, ADMIN_A_ID, "Refund issued.", "RESOLVED"));
        return id;
    }

    private int companyUnread(String conversationId) {
        return service.viewCompanyInbox(OWNER_TOKEN, COMPANY_ID).stream()
                .filter(c -> c.conversationId().equals(conversationId))
                .findFirst().orElseThrow()
                .unreadCountForViewer();
    }

    private static void memberToken(SessionManager sessions, String token, int userId) {
        when(sessions.validateToken(token)).thenReturn(true);
        when(sessions.extractUserId(token)).thenReturn(userId);
    }

    private static void adminToken(SessionManager sessions, AdminRepository admins, String token, int adminId) {
        when(sessions.isAdminToken(token)).thenReturn(true);
        when(sessions.validateAdminToken(token)).thenReturn(true);
        when(sessions.extractAdminId(token)).thenReturn(adminId);
        when(admins.findById(adminId)).thenReturn(new Admin(adminId, "admin-" + adminId, "unused-password-hash", false));
    }
}
