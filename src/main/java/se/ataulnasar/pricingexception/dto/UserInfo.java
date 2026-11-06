package se.ataulnasar.pricingexception.dto;

import se.ataulnasar.pricingexception.model.Role;

/** A user as shown to the UI's user switcher. */
public record UserInfo(String id, String name, Role role, Integer approvalLimitBps) {
}
