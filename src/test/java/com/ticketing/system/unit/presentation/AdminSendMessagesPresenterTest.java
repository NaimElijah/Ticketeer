package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MemberSearchResultDTO;
import com.ticketing.system.shared.dto.MessageDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.dto.OutreachResultDTO;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.AdminSendMessagesPresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import jakarta.persistence.PersistenceException;

class AdminSendMessagesPresenterTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private MemberQueryService memberQuery;
    private AdminSendMessagesPresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        memberQuery = mock(MemberQueryService.class);
        presenter = new AdminSendMessagesPresenter(messaging, memberQuery);
    }

    /** A single DIRECT outreach conversation (admin → member) for the grouping tests. */
    private static ConversationDTO outreach(String id, int memberId, String subject, String body) {
        return new ConversationDTO(
            id, "DIRECT", "OPEN", 1, "ADMIN", memberId, "MEMBER",
            subject, SENT_AT, SENT_AT, 0,
            List.of(new MessageDTO("m-" + id, 1, "ADMIN", body, SENT_AT, false)),
            "TicketHub Support", "member" + memberId);
    }

    // -- send -----------------------------------------------------------------

    @Test
    void GivenANullToken_WhenSend_ThenReturnsNotAuthenticatedWithoutCallingService() {
        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(null, "S", "B", List.of(1), false, false);

        assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).sendOutreach(anyString(), any());
    }

    @Test
    void GivenExplicitRecipients_WhenSend_ThenPassesIdsAndReturnsRecipientCount() {
        when(messaging.sendOutreach(eq(TOKEN), any())).thenReturn(new OutreachResultDTO(2, "conv-1"));

        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(TOKEN, "Notice", "Body", List.of(7, 9), false, false);

        AdminSendMessagesPresenter.ActionOutcome.Success ok =
            assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.Success.class, outcome);
        assertEquals(2, ok.recipientCount());

        ArgumentCaptor<OutreachRequestDTO> captor = ArgumentCaptor.forClass(OutreachRequestDTO.class);
        verify(messaging).sendOutreach(eq(TOKEN), captor.capture());
        assertEquals(new OutreachRequestDTO("Notice", "Body", List.of(7, 9), false, false), captor.getValue(),
            "the subject, body and recipients go as entered, with neither everyone flag");
    }

    @Test
    void GivenAllMembersSelected_WhenSend_ThenPassesFlagThrough() {
        when(messaging.sendOutreach(eq(TOKEN), any())).thenReturn(new OutreachResultDTO(42, "conv-1"));

        presenter.send(TOKEN, "Notice", "Body", List.of(), true, false);

        ArgumentCaptor<OutreachRequestDTO> captor = ArgumentCaptor.forClass(OutreachRequestDTO.class);
        verify(messaging).sendOutreach(eq(TOKEN), captor.capture());
        assertTrue(captor.getValue().allMembers());
    }

    @Test
    void GivenTheServiceFailsInternally_WhenSend_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("could not execute statement")).when(messaging).sendOutreach(anyString(), any());

        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(TOKEN, "S", "B", List.of(7), false, false);

        AdminSendMessagesPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnInvalidToken_WhenSend_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(messaging).sendOutreach(anyString(), any());

        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(TOKEN, "S", "B", List.of(7), false, false);

        assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- searchUsers ----------------------------------------------------------

    @Test
    void GivenANullToken_WhenSearchUsers_ThenReturnsNotAuthenticatedWithoutCallingService() {
        AdminSendMessagesPresenter.SearchOutcome outcome = presenter.searchUsers(null, "ali");

        assertInstanceOf(AdminSendMessagesPresenter.SearchOutcome.NotAuthenticated.class, outcome);
        verify(memberQuery, never()).searchByUsername(any(), any());
    }

    @Test
    void GivenTwoMatchingMembers_WhenSearchUsers_ThenReturnsTwoResultsFromMemberQueryService() {
        when(memberQuery.searchByUsername(TOKEN, "ali"))
            .thenReturn(List.of(new MemberSearchResultDTO(7, "alice"), new MemberSearchResultDTO(8, "alistair")));

        AdminSendMessagesPresenter.SearchOutcome outcome = presenter.searchUsers(TOKEN, "ali");

        AdminSendMessagesPresenter.SearchOutcome.Success ok =
            assertInstanceOf(AdminSendMessagesPresenter.SearchOutcome.Success.class, outcome);
        assertEquals(List.of(new MemberSearchResultDTO(7, "alice"), new MemberSearchResultDTO(8, "alistair")), ok.results());
    }

    @Test
    void GivenMemberQueryRejectsTheToken_WhenSearchUsers_ThenNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(memberQuery).searchByUsername(TOKEN, "ali");

        AdminSendMessagesPresenter.SearchOutcome outcome = presenter.searchUsers(TOKEN, "ali");

        assertInstanceOf(AdminSendMessagesPresenter.SearchOutcome.NotAuthenticated.class, outcome);
    }

    // -- load (grouping) ------------------------------------------------------

    @Test
    void GivenAFanOutToTwoRecipientsWithTheSameSubjectAndBody_WhenLoad_ThenGroupsThemIntoOneHistoryRow() {
        // Two recipients of the same logical send → one history row with recipientCount = 2.
        when(messaging.viewSentOutreach(TOKEN)).thenReturn(List.of(
            outreach("c1", 7, "Maintenance", "We are down tonight."),
            outreach("c2", 9, "Maintenance", "We are down tonight.")));

        AdminSendMessagesPresenter.Outcome outcome = presenter.load(TOKEN);

        AdminSendMessagesPresenter.Outcome.Success ok =
            assertInstanceOf(AdminSendMessagesPresenter.Outcome.Success.class, outcome);
        assertEquals(1, ok.history().size());
        assertEquals(2, ok.history().get(0).recipientCount());
        assertEquals("Maintenance", ok.history().get(0).subject());
    }

    @Test
    void GivenANullToken_WhenLoad_ThenReturnsNotAuthenticatedWithoutCallingService() {
        AdminSendMessagesPresenter.Outcome outcome = presenter.load(null);

        assertInstanceOf(AdminSendMessagesPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewSentOutreach(anyString());
    }

    @Test
    void GivenARuleViolation_WhenSend_ThenReturnsFailureWithTheRulesMessage() {
        BusinessRuleViolationException rule = new BusinessRuleViolationException("Choose at least one recipient.");
        doThrow(rule).when(messaging).sendOutreach(anyString(), any());

        AdminSendMessagesPresenter.ActionOutcome.Failure fail = assertInstanceOf(
            AdminSendMessagesPresenter.ActionOutcome.Failure.class,
            presenter.send(TOKEN, "S", "B", List.of(7), false, false));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenARevokedToken_WhenLoad_ThenReturnsNotAuthenticated() {
        when(messaging.viewSentOutreach(TOKEN)).thenThrow(new InvalidTokenException("revoked"));

        assertInstanceOf(AdminSendMessagesPresenter.Outcome.NotAuthenticated.class, presenter.load(TOKEN));
    }

    @Test
    void GivenAnExpiredSession_WhenLoad_ThenReturnsNotAuthenticated() {
        when(messaging.viewSentOutreach(TOKEN)).thenThrow(new SessionExpiredException());

        assertInstanceOf(AdminSendMessagesPresenter.Outcome.NotAuthenticated.class, presenter.load(TOKEN));
    }

    // ---- what a send passes on, and how the history groups and orders the fan-out (#189) ----

    @Test
    void GivenAllProducersSelected_WhenSend_ThenOnlyThatFlagGoesWithTheMessage() {
        when(messaging.sendOutreach(eq(TOKEN), any())).thenReturn(new OutreachResultDTO(5, "conv-1"));

        presenter.send(TOKEN, "Notice", "Body", List.of(), false, true);

        verify(messaging).sendOutreach(TOKEN, new OutreachRequestDTO("Notice", "Body", List.of(), false, true));
    }

    @Test
    void GivenTheMemberSearchFailsInternally_WhenSearchUsers_ThenFailureCarriesTheGenericText() {
        when(memberQuery.searchByUsername(TOKEN, "ali"))
            .thenThrow(new PersistenceException("could not prepare statement [select * from users]"));

        assertEquals(new AdminSendMessagesPresenter.SearchOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
            presenter.searchUsers(TOKEN, "ali"));
    }

    @Test
    void GivenTheHistoryFailsInternally_WhenLoad_ThenFailureCarriesTheGenericText() {
        when(messaging.viewSentOutreach(TOKEN))
            .thenThrow(new PersistenceException("could not prepare statement [select * from conversations]"));

        assertEquals(new AdminSendMessagesPresenter.Outcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
            presenter.load(TOKEN));
    }

    // A logical send is one initiator, subject and body: the same subject with another body is another send.
    @Test
    void GivenTwoSendsWithTheSameSubjectButDifferentBodies_WhenLoad_ThenEachIsItsOwnRow() {
        when(messaging.viewSentOutreach(TOKEN)).thenReturn(List.of(
            outreachAt("c1", ADMIN_ID, 7, "Maintenance", "Down tonight.", SENT_AT),
            outreachAt("c2", ADMIN_ID, 9, "Maintenance", "Down tomorrow.", SENT_AT.minusDays(1))));

        assertEquals(List.of(
                new AdminSendMessagesPresenter.SentOutreach(SENT_AT, "Maintenance", 1),
                new AdminSendMessagesPresenter.SentOutreach(SENT_AT.minusDays(1), "Maintenance", 1)),
            history());
    }

    @Test
    void GivenTwoAdminsSendTheSameMessage_WhenLoad_ThenEachAdminsSendIsItsOwnRow() {
        when(messaging.viewSentOutreach(TOKEN)).thenReturn(List.of(
            outreachAt("c1", ADMIN_ID, 7, "Maintenance", "Down tonight.", SENT_AT),
            outreachAt("c2", OTHER_ADMIN_ID, 9, "Maintenance", "Down tonight.", SENT_AT)));

        assertEquals(2, history().size(), "one row per admin's send: " + history());
    }

    @Test
    void GivenSendsListedOldestFirst_WhenLoad_ThenTheHistoryIsNewestFirst() {
        when(messaging.viewSentOutreach(TOKEN)).thenReturn(List.of(
            outreachAt("c1", ADMIN_ID, 7, "First", "a", SENT_AT.minusDays(2)),
            outreachAt("c2", ADMIN_ID, 7, "Second", "b", SENT_AT.minusDays(1)),
            outreachAt("c3", ADMIN_ID, 7, "Third", "c", SENT_AT)));

        assertEquals(List.of("Third", "Second", "First"),
            history().stream().map(AdminSendMessagesPresenter.SentOutreach::subject).toList());
    }

    // The fan-out creates one thread per recipient, a moment apart; the send happened when the first was made.
    @Test
    void GivenAFanOutWhoseThreadsWereCreatedAMomentApart_WhenLoad_ThenTheRowIsDatedByTheEarliest() {
        when(messaging.viewSentOutreach(TOKEN)).thenReturn(List.of(
            outreachAt("c1", ADMIN_ID, 7, "Maintenance", "Down tonight.", SENT_AT.plusSeconds(2)),
            outreachAt("c2", ADMIN_ID, 9, "Maintenance", "Down tonight.", SENT_AT),
            outreachAt("c3", ADMIN_ID, 11, "Maintenance", "Down tonight.", SENT_AT.plusSeconds(1))));

        assertEquals(List.of(new AdminSendMessagesPresenter.SentOutreach(SENT_AT, "Maintenance", 3)), history());
    }

    private static final int ADMIN_ID = 1;
    private static final int OTHER_ADMIN_ID = 2;

    private List<AdminSendMessagesPresenter.SentOutreach> history() {
        return assertInstanceOf(AdminSendMessagesPresenter.Outcome.Success.class, presenter.load(TOKEN)).history();
    }

    private static ConversationDTO outreachAt(String id, int adminId, int memberId, String subject, String body,
                                              LocalDateTime createdAt) {
        return new ConversationDTO(
            id, "DIRECT", "OPEN", adminId, "ADMIN", memberId, "MEMBER",
            subject, createdAt, createdAt, 0,
            List.of(new MessageDTO("m-" + id, adminId, "ADMIN", body, createdAt, false)),
            "TicketHub Support", "member" + memberId);
    }
}
