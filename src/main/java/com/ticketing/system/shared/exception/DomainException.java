package com.ticketing.system.shared.exception;

// Base for all domain-level exceptions. Application services may catch these and translate
// to DTO error responses; never let domain exceptions leak past the application layer.
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
