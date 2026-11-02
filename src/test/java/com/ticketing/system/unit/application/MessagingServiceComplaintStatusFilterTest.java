package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
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
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.organization.adapter.out.persistence.MemoryProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.shared.dto.ComplaintFilterDTO;
import com.ticketing.system.shared.dto.ConversationDTO;

// ---- An unknown status filter returns every complaint (#187) ----
//
// viewAllComplaints reads a blank status as "no filter". An unknown one is a caller's mistake, not
// a request for everything: it must be refused, not widened to the whole queue.
class MessagingServiceComplaintStatusFilterTest {

    private static final String ADMIN_TOKEN = "admin-tok";
    private static final int ADMIN_ID = 1;
    private static final int MEMBER_ID = 10;
    private static final int MEMBER_AGE = 30;
    private static final int GROUP_SENTINEL = 0;
    private static final Instant NOW = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private ConversationRepository conversations;
    private MessagingService service;

    @BeforeEach
    void setUp() {
        conversations = new MemoryConversationRepository();
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
                mock(ApplicationEventPublisher.class), Clock.fixed(NOW, ZoneOffset.UTC));

        saveComplaint("Open one");
        Conversation resolved = saveComplaint("Resolved one");
        resolved.addMessage(ADMIN_ID, ParticipantType.ADMIN, "Refund issued.", NOW_UTC);
        resolved.transitionToResolved();
        conversations.save(resolved);
    }

    @Test
    void GivenAnUnknownStatus_WhenAnAdminViewsComplaints_ThenRefusedWithIllegalArgument() {
        ComplaintFilterDTO unknown = new ComplaintFilterDTO("ARCHIVED", null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.viewAllComplaints(ADMIN_TOKEN, unknown));
    }

    @Test
    void GivenABlankStatus_WhenAnAdminViewsComplaints_ThenEveryComplaintIsListed() {
        List<ConversationDTO> listed = service.viewAllComplaints(ADMIN_TOKEN, new ComplaintFilterDTO("  ", null, null, null));

        assertEquals(List.of("Open one", "Resolved one"), subjects(listed));
    }

    @Test
    void GivenAKnownStatusInLowerCase_WhenAnAdminViewsComplaints_ThenOnlyComplaintsInThatStatusAreListed() {
        List<ConversationDTO> listed = service.viewAllComplaints(ADMIN_TOKEN,
                new ComplaintFilterDTO("resolved", null, null, null));

        assertEquals(List.of("Resolved one"), subjects(listed));
    }

    private Conversation saveComplaint(String subject) {
        Conversation complaint = Conversation.start(ConversationType.COMPLAINT,
                MEMBER_ID, ParticipantType.MEMBER, GROUP_SENTINEL, ParticipantType.ADMIN_GROUP,
                subject, "Please look into this.", NOW_UTC);
        conversations.save(complaint);
        return complaint;
    }

    // Both complaints share one timestamp, so the queue's newest-first order does not fix theirs.
    private static List<String> subjects(List<ConversationDTO> listed) {
        return listed.stream().map(ConversationDTO::subject).sorted().toList();
    }
}
