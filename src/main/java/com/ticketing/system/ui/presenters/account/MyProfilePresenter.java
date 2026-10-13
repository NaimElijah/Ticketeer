package com.ticketing.system.ui.presenters.account;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.MemberDTO;
import com.ticketing.system.identity.application.service.MemberQueryService;
import com.ticketing.system.ui.session.AuthSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code MyProfileView}. Loads the signed-in member's
 * profile projection (id, username, email) so the view can show the real email
 * instead of a placeholder.
 *
 * <p>Vaadin-free POJO that returns a sealed {@link Outcome} the view switches on
 * (never try/catch). Resolves the member by the session userId; degrades to
 * {@link Outcome.NotAuthenticated} when signed out and {@link Outcome.Failure}
 * on any service error.
 */
@Slf4j
@Component
public class MyProfilePresenter {

    private final MemberQueryService memberQueryService;

    public MyProfilePresenter(MemberQueryService memberQueryService) {
        this.memberQueryService = memberQueryService;
    }

    public sealed interface Outcome {
        record Success(MemberDTO member) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** The signed-in member's profile; NotAuthenticated when signed out, Failure on error. */
    public Outcome load() {
        Integer userId = AuthSession.userId();
        if (userId == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            return new Outcome.Success(memberQueryService.getMemberProfile(userId));
        } catch (RuntimeException e) {
            log.warn("Failed to load profile for user {}: {}", userId, e.getMessage());
            return new Outcome.Failure(e.getMessage());
        }
    }
}
