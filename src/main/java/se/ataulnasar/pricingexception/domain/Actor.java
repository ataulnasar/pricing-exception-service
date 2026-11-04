package se.ataulnasar.pricingexception.domain;

/**
 * The person or system running a command. Identity is the {@code id}; the approval limit
 * is only meaningful for a REVIEWER and is 0 for everyone else.
 */
public record Actor(String id, Role role, int approvalLimitBps) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("actor id must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("actor role must not be null");
        }
        if (approvalLimitBps < 0) {
            throw new IllegalArgumentException("approval limit must not be negative");
        }
    }

    public static Actor rm(String id) {
        return new Actor(id, Role.RM, 0);
    }

    public static Actor reviewer(String id, int approvalLimitBps) {
        return new Actor(id, Role.REVIEWER, approvalLimitBps);
    }

    public static Actor mortgageProcess(String id) {
        return new Actor(id, Role.MORTGAGE_PROCESS, 0);
    }
}
