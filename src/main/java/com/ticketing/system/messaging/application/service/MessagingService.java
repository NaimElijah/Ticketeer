package com.ticketing.system.messaging.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.dto.ComplaintFilterDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.dto.OutreachResultDTO;
import com.ticketing.system.shared.dto.RespondToComplaintRequestDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.shared.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.messaging.application.dtoMappers.ConversationMapper;
import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.shared.event.NewMessageNotice;
import com.ticketing.system.shared.event.OutreachSentNotice;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.ConversationNotFoundException;
import com.ticketing.system.shared.exception.InvalidParticipantException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.identity.domain.User;

import lombok.extern.slf4j.Slf4j;

// Centralized messaging subsystem service.
// Replaces per-User MessageInbox, per-Company Inbox, and the standalone Complaint flow.
// Covers requirements II.3.3 (complaint), II.3.10 (contact company), II.4.4 (company support
// inbox), II.6.3.1 (admin complaint queue), II.6.3.2 (admin announcements).
//
// All write paths fire the messaging→notification bridge AFTER releasing the conversation
// lock: members are notified of admin/producer messages; company owners of new inquiries.
// Admins are never notified: a notice names its recipient by bare user id, admin ids share the
// member id space, so a notice to an admin's id would reach the member with that id (ADR-012).
// Admins see complaints and replies in their queue and inbox instead.
@Service
@Slf4j
public class MessagingService {

    // Sentinel counterparty id for group descriptors (ADMIN_GROUP has no single entity id).
    private static final int GROUP_SENTINEL = 0;
    private static final int SNIPPET_MAX = 120;
    // A complaint's related-entity reference is folded into the stored body, after the details.
    private static final String RELATED_PREFIX = "\n\n[Related: ";
    private static final String RELATED_SUFFIX = "]";

    private final ConversationRepository conversationRepository;
    private final SessionManager sessionManager;
    private final AdminRepository adminRepository;
    private final UserRepository userRepository;
    private final ProductionCompanyRepository companyRepository;
    // Company-membership/authorization checks (moved off the User aggregate, task #20) used to decide
    // whether a caller may act for a company; messaging already depends on organization.
    private final CompanyMembershipService companyMembershipService;
    // Publisher for cross-context integration events (NewMessageNotice); the notifications
    // context listens for these instead of messaging calling it directly.
    private final ApplicationEventPublisher eventPublisher;
    // Stamps messages and conversations: UTC wall time (ADR-008).
    private final Clock clock;

    public MessagingService(
            ConversationRepository conversationRepository,
            SessionManager sessionManager,
            AdminRepository adminRepository,
            UserRepository userRepository,
            ProductionCompanyRepository companyRepository,
            CompanyMembershipService companyMembershipService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.conversationRepository = conversationRepository;
        this.sessionManager = sessionManager;
        this.adminRepository = adminRepository;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.companyMembershipService = companyMembershipService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // Which side of a conversation the authenticated caller is acting as.
    private record CallerSide(int id, ParticipantType type) {}

    // ---------------------------------------------------------------------------
    // Write operations
    // ---------------------------------------------------------------------------

    // II.3.10 — Member starts an INQUIRY conversation (two-way chat) with a Company.
    @Transactional
    public ConversationDTO startConversation(String token, StartConversationRequestDTO request) {
        int memberId = authenticate(token);
        int companyId = request.counterpartyId();
        ProductionCompany company = companyRepository.getCompanyById(companyId); // throws if missing

        Conversation conversation = Conversation.start(
                ConversationType.INQUIRY,
                memberId, ParticipantType.MEMBER,
                companyId, ParticipantType.COMPANY,
                request.subject(), request.firstMessageBody(), LocalDateTime.now(clock));
        conversationRepository.save(conversation);

        // Bridge — a member opened an inquiry; notify the company's owners.
        notifyCompanyOwners(company, conversation, senderLabel(ParticipantType.MEMBER), request.firstMessageBody());
        log.info("Inquiry {} started by member {} to company {}",
                conversation.getConversationId(), memberId, companyId);
        return toDTO(conversation, memberId, ParticipantType.MEMBER);
    }



    // Append a reply to an existing conversation. Sender must be a participant.
    @Transactional
    public void sendMessage(String token, SendMessageRequestDTO request) {
        Caller caller = authenticateCaller(token);
        String conversationId = request.conversationId();

        Conversation conversation;
        CallerSide sender;
        conversationRepository.lockForUpdate(conversationId);
        try {
            conversation = loadConversation(conversationId);
            // Complaints are one-shot: the admin's single reply goes through respondToComplaint,
            // never this chat path. (The domain enforces this too — belt and suspenders.)
            if (conversation.getType() == ConversationType.COMPLAINT) {
                throw new BusinessRuleViolationException(
                        "Complaints are not a chat — they accept a single admin response only");
            }
            sender = resolveCallerSide(caller, conversation);
            conversation.addMessage(sender.id(), sender.type(), request.body(), LocalDateTime.now(clock));
            conversationRepository.save(conversation);
        } finally {
            conversationRepository.unlock(conversationId);
        }

        // Bridge — notify the other side, outside the lock.
        notifyOtherParty(conversation, sender, request.body());
        log.info("Message appended to conversation {} by {} {}", conversationId, sender.type(), sender.id());
    }



    /**
     * II.3.3: the member files a COMPLAINT with the admins (counterparty ADMIN_GROUP). A related-entity
     * reference, when given, is appended to the details in the stored body.
     *
     * @param token   the member's token
     * @param request the subject, the details the member typed, and an optional related-entity reference
     * @return the new complaint, seen by the member
     * @throws IllegalArgumentException if the details are null or blank. Checked before the reference is
     *         appended, so that a reference alone never passes for details (#187). Also if the reference
     *         leaves no room for details (see {@link #complaintDetailsLimit})
     * @throws BusinessRuleViolationException if the details are longer than {@link #complaintDetailsLimit}
     *         allows, or the subject is longer than {@link TextLimits#MAX_TEXT_LENGTH}
     * @throws com.ticketing.system.shared.exception.InvalidTokenException if the token is malformed or
     *         revoked, and {@link com.ticketing.system.shared.exception.SessionExpiredException} if it
     *         has expired
     * @throws UnauthorizedActionException if the token is null or blank
     */
    @Transactional
    public ConversationDTO submitComplaint(String token, SubmitComplaintRequestDTO request) {
        int memberId = authenticate(token);
        String body = request.body();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Complaint details must not be blank");
        }
        if (hasRelatedRef(request.relatedEntityRef())) {
            // Checked here rather than left to Message, whose refusal would name the whole body's
            // limit: the member types only the details, and the reference shares their column (#186).
            TextLimits.requireAtMost("Complaint details", body,
                    complaintDetailsLimit(request.relatedEntityRef()));
            body = body + RELATED_PREFIX + request.relatedEntityRef() + RELATED_SUFFIX;
        }
        Conversation conversation = Conversation.start(
                ConversationType.COMPLAINT,
                memberId, ParticipantType.MEMBER,
                GROUP_SENTINEL, ParticipantType.ADMIN_GROUP,
                request.subject(), body, LocalDateTime.now(clock));
        conversationRepository.save(conversation);

        // No notice: the complaint reaches admins through their complaint queue (see class comment).
        log.info("Complaint {} submitted by member {}", conversation.getConversationId(), memberId);
        return toDTO(conversation, memberId, ParticipantType.MEMBER);
    }

    /**
     * The most characters a complaint's details may have when it is filed with
     * {@code relatedEntityRef}, which {@link #submitComplaint} appends to the details in the stored body.
     *
     * @param relatedEntityRef the reference the complaint will carry; {@code null} or blank for none
     * @return the details' allowance, always positive; with no reference, the whole
     *         {@link TextLimits#MAX_TEXT_LENGTH}
     * @throws IllegalArgumentException if the reference leaves no room for the details. References are
     *         built by the caller, never typed by the member, so this is the caller's mistake
     */
    public static int complaintDetailsLimit(String relatedEntityRef) {
        if (!hasRelatedRef(relatedEntityRef)) {
            return TextLimits.MAX_TEXT_LENGTH;
        }
        int allowance = TextLimits.MAX_TEXT_LENGTH
                - (RELATED_PREFIX.length() + relatedEntityRef.length() + RELATED_SUFFIX.length());
        if (allowance <= 0) {
            throw new IllegalArgumentException("A related reference of " + relatedEntityRef.length()
                    + " characters leaves no room for the complaint's details");
        }
        return allowance;
    }

    private static boolean hasRelatedRef(String relatedEntityRef) {
        return relatedEntityRef != null && !relatedEntityRef.isBlank();
    }



    // II.6.3.1 — admin sends the single response to a complaint, which resolves it (one-shot).
    @Transactional
    public void respondToComplaint(String token, RespondToComplaintRequestDTO request) {
        int adminId = requireSystemAdmin(token);
        String conversationId = request.conversationId();

        Conversation conversation;
        conversationRepository.lockForUpdate(conversationId);
        try {
            conversation = loadConversation(conversationId);
            if (conversation.getType() != ConversationType.COMPLAINT) {
                throw new BusinessRuleViolationException(
                        "Conversation " + conversationId + " is not a complaint");
            }
            // The admin's reply is the complaint's terminal response: append it, then resolve.
            // The lock serializes concurrent admins; the domain rejects a second admin reply.
            conversation.addMessage(adminId, ParticipantType.ADMIN, request.body(), LocalDateTime.now(clock));
            conversation.transitionToResolved();
            conversationRepository.save(conversation);
        } finally {
            conversationRepository.unlock(conversationId);
        }

        // Bridge — notify the member who filed the complaint.
        publishNewMessage(conversation.getInitiatorId(), conversationId,
                senderLabel(ParticipantType.ADMIN), conversation.getSubject(), request.body());
        log.info("Admin {} resolved complaint {}", adminId, conversationId);
    }

    // II.6.3.2 — admin proactive messaging. Resolves the recipient set (explicit members, and/or
    // all members, and/or all producers' owner accounts), then opens one two-way DIRECT
    // conversation per recipient so each lands in that member's Support Inbox as a chat.
    // The whole send publishes one OutreachSentNotice, not one notice per recipient: the notify-
    // queue is bounded and drops what does not fit (ADR-004, #187).
    @Transactional
    public OutreachResultDTO sendOutreach(String token, OutreachRequestDTO request) {
        int adminId = requireSystemAdmin(token);

        Set<Integer> recipientIds = new LinkedHashSet<>();
        if (request.allMembers()) {
            for (User member : userRepository.findAll()) {
                recipientIds.add(member.getUserId());
            }
        }
        if (request.allProducers()) {
            for (ProductionCompany company : companyRepository.findActive()) {
                for (Integer ownerId : company.getOwnerIds()) {
                    if (ownerId != null) recipientIds.add(ownerId);
                }
            }
        }
        if (!request.allMembers() && !request.allProducers()) {
            for (Integer memberId : safeList(request.recipientMemberIds())) {
                if (memberId != null) recipientIds.add(memberId);
            }
        }

        if (recipientIds.isEmpty()) {
            throw new BusinessRuleViolationException("Outreach matched no recipients");
        }

        List<Conversation> created = new ArrayList<>();
        List<OutreachSentNotice.Recipient> notified = new ArrayList<>();
        for (Integer memberId : recipientIds) {
            Conversation conversation = Conversation.start(
                    ConversationType.DIRECT,
                    adminId, ParticipantType.ADMIN,
                    memberId, ParticipantType.MEMBER,
                    request.subject(), request.body(), LocalDateTime.now(clock));
            conversationRepository.save(conversation);
            created.add(conversation);
            notified.add(new OutreachSentNotice.Recipient(memberId, conversation.getConversationId()));
        }
        eventPublisher.publishEvent(new OutreachSentNotice(notified,
                senderLabel(ParticipantType.ADMIN), request.subject(), snippet(request.body())));

        log.info("Admin {} sent outreach to {} recipient(s)", adminId, created.size());
        return new OutreachResultDTO(created.size(), created.get(0).getConversationId());
    }



    /**
     * Marks the thread read for the caller's side: every message the other side sent becomes read, so
     * the caller's unread count for it drops to 0. The UI calls it when the caller opens the thread.
     * The other side's count is unchanged. An admin reads for all admins: opening a complaint marks the
     * member's message read for the whole queue, and never marks an admin's reply read for the member.
     *
     * @param token the caller's member or admin token
     * @param conversationId the thread to mark read
     * @throws com.ticketing.system.shared.exception.InvalidTokenException if the token is malformed or
     *         revoked, and {@link com.ticketing.system.shared.exception.SessionExpiredException} if it
     *         has expired
     * @throws UnauthorizedActionException if the token is null or blank
     * @throws ConversationNotFoundException if no conversation has that id
     * @throws InvalidParticipantException if the caller is not a participant of the conversation
     */
    @Transactional
    public void markConversationRead(String token, String conversationId) {
        Caller caller = authenticateCaller(token);
        conversationRepository.lockForUpdate(conversationId);
        try {
            Conversation conversation = loadConversation(conversationId);
            CallerSide reader = resolveCallerSide(caller, conversation);
            conversation.markReadBy(reader.id(), reader.type());
            conversationRepository.save(conversation);
        } finally {
            conversationRepository.unlock(conversationId);
        }
    }



    // Terminal action — close a conversation (no further messages allowed).
    @Transactional
    public void closeConversation(String token, String conversationId) {
        Caller caller = authenticateCaller(token);
        conversationRepository.lockForUpdate(conversationId);
        try {
            Conversation conversation = loadConversation(conversationId);
            resolveCallerSide(caller, conversation); // authorize: caller must be a participant
            conversation.transitionToClosed();
            conversationRepository.save(conversation);
        } finally {
            conversationRepository.unlock(conversationId);
        }
    }

    // ---------------------------------------------------------------------------
    // Read operations
    // ---------------------------------------------------------------------------

    // Member-facing Support Inbox: inquiries + complaints the member opened, plus admin outreach.
    @Transactional(readOnly = true)
    public List<ConversationDTO> viewMyConversations(String token) {
        int memberId = authenticate(token);
        return conversationRepository.findMemberInbox(memberId).stream()
                .sorted(Comparator.comparing(Conversation::getLastMessageAt).reversed())
                .map(c -> toDTO(c, memberId, ParticipantType.MEMBER))
                .toList();
    }

    // Open a single thread. Caller must be a participant.
    @Transactional(readOnly = true)
    public ConversationDTO viewConversation(String token, String conversationId) {
        Caller caller = authenticateCaller(token);
        Conversation conversation = loadConversation(conversationId);
        CallerSide side = resolveCallerSide(caller, conversation); // throws if not a participant
        return toDTO(conversation, side.id(), side.type());
    }

    // II.4.4 — company support inbox: all conversations where this company is counterparty.
    @Transactional(readOnly = true)
    public List<ConversationDTO> viewCompanyInbox(String token, int companyId) {
        int callerId = authenticate(token);
        if (!callerActsForCompany(callerId, companyId)) {
            throw new UnauthorizedActionException(
                    "view company inbox", callerId);
        }
        return conversationRepository.findByCompanyAsCounterparty(companyId).stream()
                .sorted(Comparator.comparing(Conversation::getLastMessageAt).reversed())
                .map(c -> toDTO(c, companyId, ParticipantType.COMPANY))
                .toList();
    }

    // II.6.3.1 — admin queue of complaints with filters.
    @Transactional(readOnly = true)
    public List<ConversationDTO> viewAllComplaints(String token, ComplaintFilterDTO filters) {
        int adminId = requireSystemAdmin(token);
        ConversationStatus status = parseStatusOrNull(filters == null ? null : filters.status());
        List<Conversation> complaints = status != null
                ? conversationRepository.findByTypeAndStatus(ConversationType.COMPLAINT, status)
                : conversationRepository.findByType(ConversationType.COMPLAINT);

        return complaints.stream()
                .filter(c -> matchesComplaintFilter(c, filters))
                .sorted(Comparator.comparing(Conversation::getLastMessageAt).reversed())
                .map(c -> toDTO(c, adminId, ParticipantType.ADMIN))
                .toList();
    }

    // II.6.3.2 — admin "sent history": every DIRECT conversation the admin initiated.
    // The fan-out creates one conversation per recipient; callers group them into broadcasts.
    @Transactional(readOnly = true)
    public List<ConversationDTO> viewSentOutreach(String token) {
        int adminId = requireSystemAdmin(token);
        return conversationRepository.findByTypeAndInitiatorType(ConversationType.DIRECT, ParticipantType.ADMIN).stream()
                .sorted(Comparator.comparing(Conversation::getLastMessageAt).reversed())
                .map(c -> toDTO(c, adminId, ParticipantType.ADMIN))
                .toList();
    }

    // Admin Inbox — the calling admin's own DIRECT conversations (outreach they started), newest
    // first, so they can chat and close each one. Scoped to this admin (findByParticipant is an
    // exact id/type match), so it never includes another admin's outreach or complaints.
    @Transactional(readOnly = true)
    public List<ConversationDTO> viewAdminInbox(String token) {
        int adminId = requireSystemAdmin(token);
        return conversationRepository.findByParticipant(adminId, ParticipantType.ADMIN).stream()
                .filter(c -> c.getType() == ConversationType.DIRECT)
                .sorted(Comparator.comparing(Conversation::getLastMessageAt).reversed())
                .map(c -> toDTO(c, adminId, ParticipantType.ADMIN))
                .toList();
    }

    // ---------------------------------------------------------------------------
    // Authentication / authorization helpers
    // ---------------------------------------------------------------------------

    private int authenticate(String token) {
        if (!sessionManager.validateToken(token)) {
            throw new UnauthorizedActionException("Invalid or expired token");
        }
        return sessionManager.extractUserId(token);
    }

    // The authenticated caller: an admin token names an admin id, any other token goes through the
    // member path, which rejects everything but a live member token. Member and admin ids share one
    // id space, so the id alone never says which kind of caller it is.
    private Caller authenticateCaller(String token) {
        if (sessionManager.isAdminToken(token)) {
            return new Caller(sessionManager.extractAdminId(token), true);
        }
        return new Caller(authenticate(token), false);
    }

    private record Caller(int id, boolean adminToken) {}

    // A live admin token whose admin still exists. Any other token fails as the member path fails
    // it, and a live member token is unauthorized here.
    private int requireSystemAdmin(String token) {
        if (!sessionManager.isAdminToken(token)) {
            int memberId = authenticate(token);
            throw new UnauthorizedActionException("messaging admin operation", memberId);
        }
        int adminId = sessionManager.extractAdminId(token);
        if (!isAdmin(adminId)) {
            throw new UnauthorizedActionException("messaging admin operation", adminId);
        }
        return adminId;
    }

    private boolean isAdmin(int id) {
        return adminRepository.findById(id) != null;
    }

    // Resolves which side of the conversation the caller represents — the single source of
    // truth for authorization, sender-stamping, and read-tracking.
    private CallerSide resolveCallerSide(Caller caller, Conversation conversation) {
        int callerId = caller.id();
        // 1. Admin acting within an admin-involved thread (complaint queue or admin-authored).
        //    Requires an ADMIN-role token, not just adminRepository membership: the member/admin id
        //    pools overlap, so a colliding member token must not be treated as ADMIN here.
        if (caller.adminToken()) {
            if (isAdmin(callerId) && involvesAdmin(conversation, callerId)) {
                return new CallerSide(callerId, ParticipantType.ADMIN);
            }
            // An admin acts only as an admin, never as the member or company owner who shares the
            // admin's id, so steps 2 and 3 do not apply.
            throw notAParticipant(callerId, conversation);
        }
        // 2. Member acting as themselves.
        if (matchesMember(conversation, callerId)) {
            return new CallerSide(callerId, ParticipantType.MEMBER);
        }
        // 3. Company side — caller is an Owner of / holds RESPOND_TO_INQUIRIES on the company.
        Integer companyId = companyParticipantId(conversation);
        if (companyId != null && callerActsForCompany(callerId, companyId)) {
            return new CallerSide(companyId, ParticipantType.COMPANY);
        }
        throw notAParticipant(callerId, conversation);
    }

    private static InvalidParticipantException notAParticipant(int callerId, Conversation conversation) {
        return new InvalidParticipantException(
                "Caller " + callerId + " is not a participant in conversation " + conversation.getConversationId());
    }

    private boolean involvesAdmin(Conversation c, int callerId) {
        return c.getInitiatorType() == ParticipantType.ADMIN_GROUP
                || c.getCounterpartyType() == ParticipantType.ADMIN_GROUP
                || (c.getInitiatorType() == ParticipantType.ADMIN && c.getInitiatorId() == callerId)
                || (c.getCounterpartyType() == ParticipantType.ADMIN && c.getCounterpartyId() == callerId);
    }

    private boolean matchesMember(Conversation c, int callerId) {
        return (c.getInitiatorType() == ParticipantType.MEMBER && c.getInitiatorId() == callerId)
                || (c.getCounterpartyType() == ParticipantType.MEMBER && c.getCounterpartyId() == callerId);
    }

    private Integer companyParticipantId(Conversation c) {
        if (c.getInitiatorType() == ParticipantType.COMPANY) {
            return c.getInitiatorId();
        }
        if (c.getCounterpartyType() == ParticipantType.COMPANY) {
            return c.getCounterpartyId();
        }
        return null;
    }

    private boolean callerActsForCompany(int callerId, int companyId) {
        try {
            // Owner of, or holder of RESPOND_TO_INQUIRIES on, the company — resolved by userId now that
            // appointments live on the CompanyAppointment aggregate (task #20).
            return companyMembershipService.isOwnerInCompany(callerId, companyId)
                    || companyMembershipService.hasPermissionInCompany(callerId, companyId,
                            Permission.RESPOND_TO_INQUIRIES);
        } catch (UserNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------
    // Notification bridge helpers
    // ---------------------------------------------------------------------------

    private void notifyOtherParty(Conversation conversation, CallerSide sender, String body) {
        boolean senderIsInitiator = isInitiatorSide(conversation, sender);
        ParticipantType otherType = senderIsInitiator
                ? conversation.getCounterpartyType() : conversation.getInitiatorType();
        int otherId = senderIsInitiator
                ? conversation.getCounterpartyId() : conversation.getInitiatorId();
        String label = senderLabel(sender.type());

        switch (otherType) {
            case MEMBER -> publishNewMessage(otherId, conversation.getConversationId(), label,
                    conversation.getSubject(), body);
            case COMPANY -> {
                ProductionCompany company = tryGetCompany(otherId);
                if (company != null) {
                    notifyCompanyOwners(company, conversation, label, body);
                }
            }
            // Admins are never notified (see class comment); the reply shows in their inbox or queue.
            case ADMIN, ADMIN_GROUP -> { }
            default -> { /* SYSTEM — no single recipient to notify */ }
        }
    }

    private boolean isInitiatorSide(Conversation c, CallerSide side) {
        if (c.getInitiatorType() == side.type() && c.getInitiatorId() == side.id()) {
            return true;
        }
        // An ADMIN acting for the ADMIN_GROUP counts as the initiator when the group initiated.
        return side.type() == ParticipantType.ADMIN && c.getInitiatorType() == ParticipantType.ADMIN_GROUP;
    }

    private void notifyCompanyOwners(ProductionCompany company, Conversation conversation,
            String senderLabel, String body) {
        for (Integer ownerId : company.getOwnerIds()) {
            if (ownerId != null) {
                publishNewMessage(ownerId, conversation.getConversationId(), senderLabel,
                        conversation.getSubject(), body);
            }
        }
    }

    // Notifications consumes the notice after this transaction commits, off this thread (ADR-004), so a
    // notification failure can neither undo the persisted message nor reach the sender.
    private void publishNewMessage(int recipientUserId, String conversationId, String senderLabel,
            String subject, String body) {
        eventPublisher.publishEvent(new NewMessageNotice(recipientUserId, conversationId, senderLabel,
                subject, snippet(body)));
    }

    // ---------------------------------------------------------------------------
    // DTO mapping
    // ---------------------------------------------------------------------------

    // Maps a conversation to its DTO for the given viewer, resolving both parties' display names.
    private ConversationDTO toDTO(Conversation c, int viewerId, ParticipantType viewerType) {
        return new ConversationMapper().toDTO(c, viewerId, viewerType,
                resolveDisplayName(c.getInitiatorId(), c.getInitiatorType()),
                resolveDisplayName(c.getCounterpartyId(), c.getCounterpartyType()));
    }

    // Human-readable label for a conversation party: member username, company name, or a fixed
    // label for the admin side / system. Falls back to "<Kind> #id" if the entity can't be loaded.
    private String resolveDisplayName(int id, ParticipantType type) {
        return switch (type) {
            case MEMBER -> {
                try { yield userRepository.getUserById(id).getUsername(); }
                catch (RuntimeException e) { yield "Member #" + id; }
            }
            case COMPANY -> {
                try { yield companyRepository.getCompanyById(id).getName(); }
                catch (RuntimeException e) { yield "Company #" + id; }
            }
            case ADMIN, ADMIN_GROUP -> "TicketHub Support";
            case SYSTEM -> "TicketHub";
        };
    }

    // ---------------------------------------------------------------------------
    // Misc helpers
    // ---------------------------------------------------------------------------

    private Conversation loadConversation(String conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    // Best-effort company lookup — returns null instead of throwing so a missing company
    // never aborts a notification fan-out (the repository throws when the id is unknown).
    private ProductionCompany tryGetCompany(int companyId) {
        try {
            return companyRepository.getCompanyById(companyId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean matchesComplaintFilter(Conversation c, ComplaintFilterDTO filters) {
        if (filters == null) {
            return true;
        }
        if (filters.memberId() != null && c.getInitiatorId() != filters.memberId()) {
            return false;
        }
        if (filters.fromDate() != null && c.getCreatedAt().toLocalDate().isBefore(filters.fromDate())) {
            return false;
        }
        if (filters.toDate() != null && c.getCreatedAt().toLocalDate().isAfter(filters.toDate())) {
            return false;
        }
        return true;
    }

    private ConversationStatus parseStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ConversationStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String senderLabel(ParticipantType type) {
        return switch (type) {
            case MEMBER -> "a member";
            case COMPANY -> "a production company";
            case ADMIN, ADMIN_GROUP -> "a system admin";
            case SYSTEM -> "the system";
        };
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= SNIPPET_MAX ? body : body.substring(0, SNIPPET_MAX) + "…";
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }





}
