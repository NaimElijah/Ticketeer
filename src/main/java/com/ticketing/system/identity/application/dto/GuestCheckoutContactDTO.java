package com.ticketing.system.identity.application.dto;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.identity.domain.User;

/**
 * Contact info a Guest provides when checking out (D5 reversed). The Guest
 * has no User account; the email is what receipts and ticket barcodes are
 * delivered to (out-of-band — V1 doesn't actually send emails).
 *
 * <p>Validation (non-blank email format, non-blank name) happens in
 * {@code CheckoutService} so the DTO stays a pure data carrier.
 */
public record GuestCheckoutContactDTO(String email, String name) {}
