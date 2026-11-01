package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.messaging.adapter.out.persistence.MemoryConversationRepository;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.UserNotFoundException;

// ---- Broad catches hide store failures (#187) ----
//
// A party that no longer exists renders as a fallback name, and a company that no longer exists is
// skipped when its owners are notified. A store that fails is a different thing: under JPA the
// failure marks the transaction rollback-only, so swallowing it hands the caller an
// UnexpectedRollbackException without its cause. Each lookup must let the store failure through.
class MessagingServiceStoreFailureTest {

    private static final String MEMBER_TOKEN = "member-tok";
    private static final int MEMBER_ID = 10;
    private static final int MEMBER_AGE = 30;
    private static final int COMPANY_ID = 5;
    private static final int GROUP_SENTINEL = 0;
    private static final Instant NOW = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private ConversationRepository conversations;
    private UserRepository users;
    private ProductionCompanyRepository companies;
    private ApplicationEventPublisher eventPublisher;
    private MessagingService service;

    @BeforeEach
    void setUp() {
        conversations = new MemoryConversationRepository();
        users = mock(UserRepository.class);
        companies = mock(ProductionCompanyRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(sessions.extractUserId(MEMBER_TOKEN)).thenReturn(MEMBER_ID);
        service = new MessagingService(conversations, sessions, mock(AdminRepository.class), users,
                companies, mock(CompanyMembershipService.class), eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void GivenTheUserStoreFails_WhenAMemberListsTheirConversations_ThenTheStoreFailurePropagates() {
        saveComplaint();
        when(users.getUserById(MEMBER_ID)).thenThrow(storeDown());

        assertThrows(DataAccessResourceFailureException.class, () -> service.viewMyConversations(MEMBER_TOKEN));
    }

    @Test
    void GivenTheCompanyStoreFails_WhenAMemberListsTheirConversations_ThenTheStoreFailurePropagates() {
        saveInquiry();
        when(users.getUserById(MEMBER_ID)).thenReturn(member());
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(storeDown());

        assertThrows(DataAccessResourceFailureException.class, () -> service.viewMyConversations(MEMBER_TOKEN));
    }

    @Test
    void GivenTheCompanyStoreFails_WhenAMemberRepliesToAnInquiry_ThenTheStoreFailurePropagates() {
        Conversation inquiry = saveInquiry();
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(storeDown());

        assertThrows(DataAccessResourceFailureException.class, () -> service.sendMessage(MEMBER_TOKEN,
                new SendMessageRequestDTO(inquiry.getConversationId(), 0, null, "Is the lot free?")));
    }

    @Test
    void GivenTheMemberNoLongerExists_WhenTheyListTheirConversations_ThenTheMemberFallbackNameIsShown() {
        saveComplaint();
        when(users.getUserById(MEMBER_ID)).thenThrow(new UserNotFoundException(MEMBER_ID));

        ConversationDTO listed = service.viewMyConversations(MEMBER_TOKEN).get(0);

        assertEquals("Member #" + MEMBER_ID, listed.initiatorDisplayName());
    }

    @Test
    void GivenTheCompanyNoLongerExists_WhenAMemberListsTheirConversations_ThenTheCompanyFallbackNameIsShown() {
        saveInquiry();
        when(users.getUserById(MEMBER_ID)).thenReturn(member());
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        ConversationDTO listed = service.viewMyConversations(MEMBER_TOKEN).get(0);

        assertEquals("Company #" + COMPANY_ID, listed.counterpartyDisplayName());
    }

    @Test
    void GivenTheCompanyNoLongerExists_WhenAMemberRepliesToAnInquiry_ThenTheReplyIsStoredAndNoOneIsNotified() {
        Conversation inquiry = saveInquiry();
        when(companies.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        service.sendMessage(MEMBER_TOKEN,
                new SendMessageRequestDTO(inquiry.getConversationId(), 0, null, "Is the lot free?"));

        assertEquals(2, conversations.findById(inquiry.getConversationId()).orElseThrow().getMessages().size(),
                "the reply is stored");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private Conversation saveComplaint() {
        Conversation complaint = Conversation.start(ConversationType.COMPLAINT,
                MEMBER_ID, ParticipantType.MEMBER, GROUP_SENTINEL, ParticipantType.ADMIN_GROUP,
                "Refund", "Where is my refund?", NOW_UTC);
        conversations.save(complaint);
        return complaint;
    }

    private Conversation saveInquiry() {
        Conversation inquiry = Conversation.start(ConversationType.INQUIRY,
                MEMBER_ID, ParticipantType.MEMBER, COMPANY_ID, ParticipantType.COMPANY,
                "Parking?", "Is there parking near the venue?", NOW_UTC);
        conversations.save(inquiry);
        return inquiry;
    }

    private static DataAccessResourceFailureException storeDown() {
        return new DataAccessResourceFailureException("connection refused");
    }

    private static User member() {
        return new User(MEMBER_ID, "member", "member@example.test", "unused-password-hash", MEMBER_AGE);
    }
}
