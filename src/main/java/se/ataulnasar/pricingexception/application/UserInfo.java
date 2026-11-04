package se.ataulnasar.pricingexception.application;

import se.ataulnasar.pricingexception.domain.Role;

/** A user as shown to the UI's user switcher. */
public record UserInfo(String id, String name, Role role, Integer approvalLimitBps) {
}
