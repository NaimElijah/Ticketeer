package com.ticketing.system.identity.application.dto;

/**
 * Contact info a Guest provides when checking out (D5 reversed): an email and a name. The Guest
 * has no User account.
 *
 * <p>Nothing uses this record yet: {@code CheckoutService.checkoutGuest} takes the guest's email as
 * a plain string.
 */
public record GuestCheckoutContactDTO(String email, String name) {}
