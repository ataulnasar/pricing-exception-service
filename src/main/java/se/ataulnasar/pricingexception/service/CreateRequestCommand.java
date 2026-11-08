package se.ataulnasar.pricingexception.service;

/** The parsed create body. Its canonical form is what an Idempotency-Key hash covers. */
public record CreateRequestCommand(String applicationId, int discountBps, String reason) implements IdempotentCommand {

    @Override
    public String canonicalForm() {
        return IdempotentCommand.canonical("create", applicationId, Integer.toString(discountBps), reason);
    }
}
