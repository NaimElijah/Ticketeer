package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.OpenedThreads;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.ConversationClosedException;
import com.ticketing.system.testutil.LogCapture;
import static org.mockito.Mockito.verifyNoInteractions;

class CompanyInquiryInboxPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private CompanyManagementService companyManagement;
    private CompanyInquiryInboxPresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        companyManagement = mock(CompanyManagementService.class);
        presenter = new CompanyInquiryInboxPresenter(messaging, companyManagement);
    }

    private static MyCompanyDTO company(int id, String name) {
        return new MyCompanyDTO(id, name, "Founder");
    }

    private static ConversationDTO conversation(String id, String type) {
        return new ConversationDTO(
            id, type, "OPEN", 1, "MEMBER", 7, "COMPANY",
            "Subject " + id, LAST_MESSAGE_AT, LAST_MESSAGE_AT, 0, List.of(),
            "alice", "Acme");
    }

    // -- loadFor --------------------------------------------------------------

    @Test
    void GivenANullToken_WhenLoadFor_ThenReturnsNotAuthenticatedWithoutCallingServices() {
        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(null, 7);

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(companyManagement, never()).findMyCompanies(anyString());
        verify(messaging, never()).viewCompanyInbox(anyString(), anyInt());
    }

    @Test
    void GivenNoCompanies_WhenLoadFor_ThenReturnsNoCompanyWithoutQueryingInbox() {
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of());

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NoCompany.class, outcome);
        verify(messaging, never()).viewCompanyInbox(anyString(), anyInt());
    }

    @Test
    void GivenARequestedCompanyAndAMixedInbox_WhenLoadFor_ThenSelectsRequestedCompanyAndKeepsOnlyInquiries() {
        MyCompanyDTO other = company(9, "Other");
        MyCompanyDTO acme = company(7, "Acme");
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(other, acme));
        ConversationDTO inquiry = conversation("c1", "INQUIRY");
        ConversationDTO direct = conversation("c2", "DIRECT");
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenReturn(List.of(inquiry, direct));

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, 7);

        CompanyInquiryInboxPresenter.Outcome.Success ok =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Success.class, outcome);
        assertEquals(7, ok.selected().companyId());
        // Only member inquiries are shown (the inbox keeps INQUIRY threads).
        assertEquals(List.of(inquiry), ok.conversations());
        verify(messaging).viewCompanyInbox(TOKEN, 7);
    }

    @Test
    void GivenANullCompanyId_WhenLoadFor_ThenDefaultsToFirstCompany() {
        MyCompanyDTO first = company(7, "Acme");
        MyCompanyDTO second = company(9, "Other");
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(first, second));
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenReturn(List.of());

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        CompanyInquiryInboxPresenter.Outcome.Success ok =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Success.class, outcome);
        assertEquals(7, ok.selected().companyId());
        verify(messaging).viewCompanyInbox(TOKEN, 7);
    }

    @Test
    void GivenAnInvalidToken_WhenLoadFor_ThenReturnsNotAuthenticated() {
        when(companyManagement.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, 7);

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NotAuthenticated.class, outcome);
    }

    @Test
    void GivenTheServiceFailsInternally_WhenLoadFor_ThenReturnsGenericFailure() {
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(company(7, "Acme")));
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenThrow(new RuntimeException("inbox down"));

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, 7);

        CompanyInquiryInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenTheInboxFailsInternally_WhenLoadFor_ThenTheReasonIsTheGenericText() {
        // A non-domain failure is an internal error: its text must not reach the user (CWE-209).
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(company(7, "Acme")));
        when(messaging.viewCompanyInbox(TOKEN, 7))
            .thenThrow(new PersistenceException("could not extract ResultSet [select c.counterparty_id from conversations c]"));

        CompanyInquiryInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Failure.class, presenter.loadFor(TOKEN, 7));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenClosingFailsInternally_WhenClose_ThenTheReasonIsTheGenericText() {
        doThrow(new NullPointerException("Cannot invoke \"Conversation.close()\" because \"conversation\" is null"))
            .when(messaging).closeConversation(TOKEN, "c1");

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Failure.class, presenter.close(TOKEN, "c1"));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // -- reply ----------------------------------------------------------------

    @Test
    void GivenANullToken_WhenReply_ThenReturnsNotAuthenticatedWithoutCallingService() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.reply(null, "c1", "hi");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).sendMessage(anyString(), any());
    }

    @Test
    void GivenTheServiceAcceptsTheMessage_WhenReply_ThenReturnsSuccessAndSendsConversationIdAndBody() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome =
            presenter.reply(TOKEN, "c1", "on it — accessible seats held");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<SendMessageRequestDTO> captor =
            ArgumentCaptor.forClass(SendMessageRequestDTO.class);
        verify(messaging).sendMessage(eq(TOKEN), captor.capture());
        assertEquals("c1", captor.getValue().conversationId());
        assertEquals("on it — accessible seats held", captor.getValue().body());
    }

    @Test
    void GivenTheServiceFailsInternally_WhenReply_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("could not execute statement"))
            .when(messaging).sendMessage(anyString(), any());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "late reply");

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnInvalidToken_WhenReply_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).sendMessage(anyString(), any());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "hi");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- close ----------------------------------------------------------------

    @Test
    void GivenANullToken_WhenClose_ThenReturnsNotAuthenticatedWithoutCallingService() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(null, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).closeConversation(anyString(), anyString());
    }

    @Test
    void GivenTheServiceAcceptsTheClose_WhenClose_ThenReturnsSuccessAndClosesConversation() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).closeConversation(TOKEN, "c1");
    }

    @Test
    void GivenTheServiceFailsInternally_WhenClose_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("could not execute statement"))
            .when(messaging).closeConversation(anyString(), anyString());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnInvalidToken_WhenClose_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).closeConversation(anyString(), anyString());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- loadOne --------------------------------------------------------------

    @Test
    void GivenANullToken_WhenLoadOne_ThenReturnsNotAuthenticatedWithoutViewingTheConversation() {
        CompanyInquiryInboxPresenter.SingleOutcome outcome = presenter.loadOne(null, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewConversation(anyString(), anyString());
    }

    @Test
    void GivenAnExistingConversation_WhenLoadOne_ThenReturnsConversation() {
        ConversationDTO inquiry = conversation("c1", "INQUIRY");
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(inquiry);

        CompanyInquiryInboxPresenter.SingleOutcome outcome = presenter.loadOne(TOKEN, "c1");

        CompanyInquiryInboxPresenter.SingleOutcome.Success ok =
            assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.Success.class, outcome);
        assertEquals(inquiry, ok.conversation());
    }

    @Test
    void GivenAClosedConversation_WhenReply_ThenReturnsFailureWithTheRulesMessage() {
        ConversationClosedException rule = new ConversationClosedException("c1");
        doThrow(rule).when(messaging).sendMessage(anyString(), any());

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail = assertInstanceOf(
            CompanyInquiryInboxPresenter.ActionOutcome.Failure.class, presenter.reply(TOKEN, "c1", "late reply"));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- Nothing ever marks a conversation read (#187) ----

    @Test
    void GivenAnInquiryThread_WhenTheCompanyOpensIt_ThenTheThreadIsMarkedRead() {
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(conversation("c1", "INQUIRY"));

        presenter.loadOne(TOKEN, "c1");

        verify(messaging).markConversationRead(TOKEN, "c1");
    }

    // ---- Mark read only on an explicit open (#187) ----
    //
    // A thread is marked read when the reader opens it, never when the view merely shows it, because a
    // mark reads it for the reader's whole side. The view keeps an OpenedThreads; the presenter decides.

    private static final LocalDateTime LAST_MESSAGE_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private static ConversationDTO unreadThread(String id, int unread, LocalDateTime lastMessageAt) {
        return new ConversationDTO(id, "INQUIRY", "OPEN", 1, "MEMBER", 7, "COMPANY", "Subject " + id,
                LAST_MESSAGE_AT, lastMessageAt, unread, List.of(), "alice", "Acme");
    }

    @Test
    void GivenAnUnreadThread_WhenTheCompanyOpensIt_ThenItIsMarkedReadAndItsBadgeIsCleared() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.open(TOKEN, thread, opened);

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).markConversationRead(TOKEN, "c1");
        assertEquals(Optional.empty(), presenter.badge(thread, opened));
    }

    @Test
    void GivenAnUnreadThread_WhenTheInboxIsLoadedAndBadged_ThenNothingIsMarkedReadAndTheBadgeShowsTheUnreadCount() {
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(new MyCompanyDTO(7, "Acme", "Founder")));
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenReturn(List.of(thread));
        List<ConversationDTO> listed = assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Success.class,
                presenter.loadFor(TOKEN, 7)).conversations();

        List<Optional<String>> badges = listed.stream().map(c -> presenter.badge(c, new OpenedThreads())).toList();

        assertEquals(List.of(Optional.of("2")), badges);
        verify(messaging, never()).markConversationRead(anyString(), anyString());
    }

    @Test
    void GivenAnOpenedThread_WhenTheSameListIsRenderedAgain_ThenItsBadgeStaysCleared() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        presenter.open(TOKEN, thread, opened);

        ConversationDTO sameThreadRenderedAgain = unreadThread("c1", 2, LAST_MESSAGE_AT);

        assertEquals(Optional.empty(), presenter.badge(sameThreadRenderedAgain, opened));
    }

    @Test
    void GivenAnOpenedThreadThatGetsANewerMessage_WhenTheListIsReloaded_ThenItsBadgeShowsTheNewCount() {
        OpenedThreads opened = new OpenedThreads();
        presenter.open(TOKEN, unreadThread("c1", 2, LAST_MESSAGE_AT), opened);

        ConversationDTO reloaded = unreadThread("c1", 1, LAST_MESSAGE_AT.plusMinutes(5));

        assertEquals(Optional.of("1"), presenter.badge(reloaded, opened));
    }

    @Test
    void GivenAThreadWithNothingUnread_WhenItsRowIsRendered_ThenItHasNoBadge() {
        assertEquals(Optional.empty(), presenter.badge(unreadThread("c1", 0, LAST_MESSAGE_AT), new OpenedThreads()));
    }

    @Test
    void GivenTheMarkFailsInternally_WhenOpened_ThenTheGenericReasonAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new RuntimeException("could not execute statement [update messages set is_read=?]"))
            .when(messaging).markConversationRead(anyString(), anyString());

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail = assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Failure.class,
                presenter.open(TOKEN, thread, opened));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenNoToken_WhenOpened_ThenNotAuthenticatedNothingIsMarkedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(null, thread, opened));

        verify(messaging, never()).markConversationRead(anyString(), anyString());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenAnExpiredToken_WhenOpened_ThenNotAuthenticatedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new SessionExpiredException()).when(messaging).markConversationRead(anyString(), anyString());

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(TOKEN, thread, opened));

        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenARevokedToken_WhenOpened_ThenNotAuthenticatedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new InvalidTokenException("revoked")).when(messaging).markConversationRead(anyString(), anyString());

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(TOKEN, thread, opened));

        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    // ---- The respond page survives a failed mark-read (#187) ----
    //
    // Marking the thread read is a side effect of opening it. When the mark fails, the reader still
    // gets the thread; only a dead session, expired or revoked, stops the page.

    @Test
    void GivenTheMarkFailsInternally_WhenLoadOne_ThenTheThreadIsStillShown() {
        ConversationDTO thread = conversation("c1", "INQUIRY");
        doThrow(new RuntimeException("could not execute statement [update messages set is_read=?]"))
            .when(messaging).markConversationRead(TOKEN, "c1");
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(thread);

        CompanyInquiryInboxPresenter.SingleOutcome.Success shown =
            assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.Success.class, presenter.loadOne(TOKEN, "c1"));

        assertEquals(thread, shown.conversation());
    }

    // As a failed row-click open logs it: at ERROR with the exception, not only LoggingAspect's one-line WARN.
    @Test
    void GivenTheMarkFailsInternally_WhenLoadOne_ThenTheFailureIsLoggedAtErrorWithItsExceptionAndTheThreadIsShown() {
        ConversationDTO thread = conversation("c1", "INQUIRY");
        doThrow(new PersistenceException("could not execute statement [update messages set is_read=?]"))
            .when(messaging).markConversationRead(TOKEN, "c1");
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(thread);

        try (LogCapture logs = LogCapture.of(ServiceErrors.class)) {
            CompanyInquiryInboxPresenter.SingleOutcome.Success shown = assertInstanceOf(
                CompanyInquiryInboxPresenter.SingleOutcome.Success.class, presenter.loadOne(TOKEN, "c1"));

            assertEquals(thread, shown.conversation());
            assertEquals(List.of(PersistenceException.class.getName()), logs.throwableClassNamesAt(Level.ERROR));
        }
    }

    @Test
    void GivenTheSessionExpiredWhenMarking_WhenLoadOne_ThenNotAuthenticatedAndTheThreadIsNotLoaded() {
        doThrow(new SessionExpiredException()).when(messaging).markConversationRead(TOKEN, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated.class, presenter.loadOne(TOKEN, "c1"));

        verify(messaging, never()).viewConversation(anyString(), anyString());
    }

    @Test
    void GivenTheTokenRevokedWhenMarking_WhenLoadOne_ThenNotAuthenticatedAndTheThreadIsNotLoaded() {
        doThrow(new InvalidTokenException("revoked")).when(messaging).markConversationRead(TOKEN, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated.class, presenter.loadOne(TOKEN, "c1"));

        verify(messaging, never()).viewConversation(anyString(), anyString());
    }

    // Marked first, so the thread the page shows already counts nothing unread.
    @Test
    void GivenAThread_WhenTheCompanyOpensTheRespondPage_ThenItIsMarkedReadBeforeItIsLoaded() {
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(conversation("c1", "INQUIRY"));

        presenter.loadOne(TOKEN, "c1");

        InOrder order = inOrder(messaging);
        order.verify(messaging).markConversationRead(TOKEN, "c1");
        order.verify(messaging).viewConversation(TOKEN, "c1");
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenAnExpiredSession_WhenLoadFor_ThenReturnsNotAuthenticated() {
        when(companyManagement.findMyCompanies(TOKEN)).thenThrow(new SessionExpiredException());

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NotAuthenticated.class, presenter.loadFor(TOKEN, 7));
    }

    // ---- a signed-out respond page touches nothing; a dead token or a failed read on the second call (#189) ----

    @Test
    void GivenANullToken_WhenLoadOne_ThenNothingIsMarkedReadOrViewed() {
        assertEquals(new CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated(), presenter.loadOne(null, "c1"));

        verifyNoInteractions(messaging, companyManagement);
    }

    @Test
    void GivenATokenRevokedBetweenTheCompaniesAndTheInbox_WhenLoadFor_ThenReturnsNotAuthenticated() {
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(company(7, "Acme")));
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenThrow(new InvalidTokenException("revoked"));

        assertEquals(new CompanyInquiryInboxPresenter.Outcome.NotAuthenticated(), presenter.loadFor(TOKEN, 7));
    }

    @Test
    void GivenATokenRevokedAfterTheMark_WhenLoadOne_ThenReturnsNotAuthenticated() {
        when(messaging.viewConversation(TOKEN, "c1")).thenThrow(new InvalidTokenException("revoked"));

        assertEquals(new CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated(), presenter.loadOne(TOKEN, "c1"));
    }

    @Test
    void GivenTheThreadFailsToLoad_WhenLoadOne_ThenFailureCarriesTheGenericText() {
        when(messaging.viewConversation(TOKEN, "c1"))
            .thenThrow(new PersistenceException("could not extract ResultSet [select * from conversations]"));

        assertEquals(new CompanyInquiryInboxPresenter.SingleOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
            presenter.loadOne(TOKEN, "c1"));
    }
}
