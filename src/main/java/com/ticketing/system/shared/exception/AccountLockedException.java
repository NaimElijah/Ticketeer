package com.ticketing.system.shared.exception;

// Thrown by member and admin sign-in (#290 / SLR.2 #148) when an account is temporarily locked
// after too many failed attempts. Distinct from AuthenticationFailedException so the view can show
// a "locked, try again later" message rather than "invalid credentials".
public class AccountLockedException extends DomainException {

    public AccountLockedException(String message) {
        super(message);
    }
}
