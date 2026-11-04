package se.ataulnasar.pricingexception.application;

import java.util.UUID;

/** The parsed supersede request: which PENDING request to replace, the version seen, and the new terms. */
public record SupersedeCommand(UUID requestId, long expectedVersion, int discountBps, String reason)
        implements IdempotentCommand {

    @Override
    public String canonicalForm() {
        return IdempotentCommand.canonical("supersede", requestId.toString(), Long.toString(expectedVersion),
                Integer.toString(discountBps), reason);
    }
}
