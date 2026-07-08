package com.ticketing.system.shared.dto;

// This is a simple DTO to represent the buyer context (member or guest) in the reservation service, without exposing the internal details of the user or session management.

// if userId is not null, then it's a member, otherwise it's a guest (identified by sessionId).
public record BuyerContextDTO(Integer userId, String sessionId) {
    
        public static BuyerContextDTO member(int userId) {
            return new BuyerContextDTO(userId, null);
        }

        public static BuyerContextDTO guest(String sessionId) {
            return new BuyerContextDTO(null, sessionId);
        }

        public boolean isMember() {
            return userId != null;
        }

        public boolean isGuest() {
            return userId == null;
        }
}