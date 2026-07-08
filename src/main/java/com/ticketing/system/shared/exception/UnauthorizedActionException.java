package com.ticketing.system.shared.exception;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.organization.domain.ProductionCompany;

// Thrown when an actor attempts an action they're not permitted to perform.
// Per lecture 2: authorization is a domain concern, not just an app-service concern.
// Used by ProductionCompany (UC-19/21/22/23/24/25), MemberAccountService (UC-16),
// SystemAdminService (UC-31/32).
public class UnauthorizedActionException extends DomainException {

    public UnauthorizedActionException(String action) {
        super("Not authorized to perform action: " + action);
    }

    public UnauthorizedActionException(String action, Object actorId) {
        super("Actor " + actorId + " not authorized to perform action: " + action);
    }
}
