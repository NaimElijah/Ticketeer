package com.ticketing.system.shared.dto;

/**
 * Who a reservation belongs to, member or guest, without exposing the user or session internals.
 * A member has a {@code userId}; a guest has none and is identified by {@code sessionId}.
 *
 * @param userId    the member's id; {@code null} for a guest
 * @param sessionId the guest's session id, which is the guest's credential; {@code null} for a member.
 *                  {@link #toString()} leaves it out
 */
public record BuyerContextDTO(Integer userId, String sessionId) {
    
        /** Returns the context of the member with {@code userId}. */
        public static BuyerContextDTO member(int userId) {
            return new BuyerContextDTO(userId, null);
        }

        /** Returns the context of the guest whose session id is {@code sessionId}. */
        public static BuyerContextDTO guest(String sessionId) {
            return new BuyerContextDTO(null, sessionId);
        }

        /** Returns whether the buyer is a member, that is, whether {@code userId} is set. */
        public boolean isMember() {
            return userId != null;
        }

        /** Returns whether the buyer is a guest, that is, whether {@code userId} is not set. */
        public boolean isGuest() {
            return userId == null;
        }

        /**
         * Leaves out the guest session id, which is the guest's credential, so a logged buyer or an
         * exception message built from one does not carry it (SLR.2).
         */
        @Override
        public String toString() {
            return "BuyerContextDTO[userId=" + userId + "]";
        }
}