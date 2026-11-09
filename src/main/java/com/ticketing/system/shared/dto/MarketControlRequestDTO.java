package com.ticketing.system.shared.dto;

/**
 * Input to {@code SystemAdminService.openMarket} and {@code closeMarket} (UC-32).
 *
 * <p>{@code action} is not read: the method called decides whether the market opens or closes.
 * {@code reason} is logged by {@code closeMarket}, which replaces a blank one with a default;
 * {@code openMarket} ignores it.
 *
 * @param action {@code OPEN} or {@code CLOSE}
 * @param token  the requesting admin's session credential, which the service checks with
 *               {@code requireSystemAdmin}; {@link #toString()} leaves it out
 */
public record MarketControlRequestDTO(
    String action,
    String reason,
    String token
) {

    /**
     * Leaves out the token, which is the admin's session credential, so a logged request or an
     * exception message built from one does not carry it (SLR.2).
     */
    @Override
    public String toString() {
        return "MarketControlRequestDTO[action=" + action + ", reason=" + reason + "]";
    }
}
