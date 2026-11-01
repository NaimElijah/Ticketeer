package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.messaging.adapter.out.persistence.MemoryConversationRepository;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.exception.DomainException;

// ---- Outreach recipient ids are not validated (#187) ----
//
// An outreach is one admin action, and sendOutreach already refuses a send that matches no recipient
// outright. So an explicit recipient list naming an id with no member refuses the whole send, before
// any conversation is stored or any notice published: a partial send would leave the admin unable to
// tell who got it. The refusal is a DomainException, whose message reaches the admin (ServiceErrors).
class MessagingServiceOutreachRecipientTest {

    private static final String ADMIN_TOKEN = "admin-tok";
    private static final int ADMIN_ID = 1;
    private static final int MEMBER_ID = 10;
    private static final int MEMBER_AGE = 30;
    private static final int NO_SUCH_MEMBER_ID = 999;
    // Member ids are minted from 1; 0 is the group sentinel and never a member.
    private static final int ID_ZERO = 0;
    private static final Instant NOW = Instant.parse("2027-07-15T17:00:00Z");

    private ConversationRepository conversations;
    private ApplicationEventPublisher eventPublisher;
    private MessagingService service;

    @BeforeEach
    void setUp() {
        conversations = new MemoryConversationRepository();
        eventPublisher = mock(ApplicationEventPublisher.class);
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.isAdminToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessions.validateAdminToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessions.extractAdminId(ADMIN_TOKEN)).thenReturn(ADMIN_ID);
        AdminRepository admins = mock(AdminRepository.class);
        when(admins.findById(ADMIN_ID)).thenReturn(new Admin(ADMIN_ID, "admin", "unused-password-hash", true));
        MemoryUserRepository users = new MemoryUserRepository();
        users.save(new User(MEMBER_ID, "member", "member@example.test", "unused-password-hash", MEMBER_AGE));
        service = new MessagingService(conversations, sessions, admins, users,
                new MemoryProductionCompanyRepository(), mock(CompanyMembershipService.class),
                eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void GivenARecipientIdWithNoMember_WhenAnAdminSendsOutreach_ThenTheWholeSendIsRefusedAndNothingIsStored() {
        OutreachRequestDTO request = outreachTo(List.of(MEMBER_ID, NO_SUCH_MEMBER_ID));

        assertThrows(DomainException.class, () -> service.sendOutreach(ADMIN_TOKEN, request));

        assertNothingSent();
    }

    @Test
    void GivenRecipientIdZero_WhenAnAdminSendsOutreach_ThenTheWholeSendIsRefusedAndNothingIsStored() {
        OutreachRequestDTO request = outreachTo(List.of(MEMBER_ID, ID_ZERO));

        assertThrows(DomainException.class, () -> service.sendOutreach(ADMIN_TOKEN, request));

        assertNothingSent();
    }

    @Test
    void GivenOnlyExistingMembers_WhenAnAdminSendsOutreach_ThenEachGetsAConversation() {
        service.sendOutreach(ADMIN_TOKEN, outreachTo(List.of(MEMBER_ID)));

        List<Conversation> sent = conversations.findByType(ConversationType.DIRECT);
        assertEquals(1, sent.size());
        assertEquals(MEMBER_ID, sent.get(0).getCounterpartyId());
    }

    private void assertNothingSent() {
        assertTrue(conversations.findByType(ConversationType.DIRECT).isEmpty(),
                () -> "no conversation is stored, found: " + conversations.findByType(ConversationType.DIRECT).size());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private static OutreachRequestDTO outreachTo(List<Integer> recipientIds) {
        return new OutreachRequestDTO("Venue change", "The show moves to Hall B.", recipientIds, false, false);
    }
}
