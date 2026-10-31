package com.ticketing.system.ui.presenters.account;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code MyProfileView}. Loads the signed-in member's
 * profile projection (id, username, email) so the view can show the real email
 * instead of a placeholder.
 *
 * <p>Vaadin-free POJO that returns a sealed {@link Outcome} the view switches on
 * (never try/catch). Resolves the member through {@link SessionIdentity}, as every member path
 * does: an admin's session stores the admin's id, and admin and member ids share one id space
 * (ADR-012), so the stored id alone would show an admin the profile of the member with that id.
 * Degrades to {@link Outcome.NotAuthenticated} for anyone who is not a live member, an admin
 * included, and to {@link Outcome.Failure} on any service error.
 */
@Slf4j
@Component
public class MyProfilePresenter {

    private final MemberQueryService memberQueryService;
    private final SessionIdentity identity;

    public MyProfilePresenter(MemberQueryService memberQueryService, SessionIdentity identity) {
        this.memberQueryService = memberQueryService;
        this.identity = identity;
    }

    public sealed interface Outcome {
        record Success(MemberDTO member) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** The live member's profile; NotAuthenticated for a guest or an admin, Failure on error. */
    public Outcome load() {
        String memberToken = identity.memberToken();
        if (memberToken == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            return new Outcome.Success(memberQueryService.getMemberProfile(memberToken));
        } catch (RuntimeException e) {
            log.warn("Failed to load profile: {}", e.getMessage());
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }
}
