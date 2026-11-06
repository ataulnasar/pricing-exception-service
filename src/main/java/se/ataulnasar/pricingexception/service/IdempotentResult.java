package se.ataulnasar.pricingexception.service;

import java.util.UUID;

/**
 * The response of a keyed create: the HTTP status and the rendered body exactly as first produced,
 * plus what the headers are derived from. {@code replayed} is true when it came from the store.
 */
public record IdempotentResult(int status, String body, UUID requestId, long version, boolean replayed) {
}
