package se.ataulnasar.pricingexception.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import se.ataulnasar.pricingexception.persistence.IdempotencyRecordEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Idempotency-Key handling (design decision 2): same key and same body replay the first response,
 * same key and a different body is rejected. The hash covers the command's canonical form (the
 * parsed body, never the raw bytes), so field order, whitespace and unknown fields do not matter.
 */
@Service
public class IdempotencyService {

    /** After a duplicate key the winner has committed; one or two re-reads are enough. */
    static final Duration CLASH_WAIT = Duration.ofMillis(500);
    /** After a lock wait timed out the winner may still be running; wait a little longer for it. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    static final Duration POLL = Duration.ofMillis(20);

    private final IdempotencyTransactions tx;
    private final JsonMapper json;

    public IdempotencyService(IdempotencyTransactions tx, JsonMapper json) {
        this.tx = tx;
        this.json = json;
    }

    /**
     * Runs {@code action} once per (caller, key, body) and replays its rendered response afterwards.
     *
     * @param successStatus the HTTP status the first response has (201 for create)
     */
    public IdempotentResult execute(String callerId, String key, IdempotentCommand command, int successStatus,
                                    Supplier<RequestView> action, Function<RequestView, String> renderBody) {
        String hash = hash(command);

        Optional<IdempotencyRecordEntity> stored = tx.find(callerId, key);
        if (stored.isPresent()) {
            return replay(stored.get(), hash, key);
        }
        try {
            return tx.claimAndRun(callerId, key, hash, successStatus, action, renderBody);
        } catch (DataIntegrityViolationException e) {
            if (!namesIdempotencyRecord(e)) {
                throw e; // our own create failed on another constraint (e.g. INV-04); nothing to replay
            }
            // Lost the claim to a concurrent request with the same key, which has committed (or is
            // about to): what it stored is our answer.
            return awaitWinner(callerId, key, CLASH_WAIT).map(w -> replay(w, hash, key)).orElseThrow(() -> e);
        } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
            // Timed out waiting for the claim's lock: the first request with this key is still running.
            return awaitWinner(callerId, key, LOCK_WAIT).map(w -> replay(w, hash, key))
                    .orElseThrow(() -> ApiProblem.idempotencyInProgress(key));
        }
    }

    private IdempotentResult replay(IdempotencyRecordEntity record, String hash, String key) {
        if (!record.getRequestHash().equals(hash)) {
            throw ApiProblem.idempotencyKeyReused(key);
        }
        if (record.getResponseStatus() == IdempotencyRecordEntity.IN_PROGRESS) {
            // Defensive: a claim is completed in its own transaction, so this is never committed.
            throw ApiProblem.idempotencyInProgress(key);
        }
        JsonNode body = json.readTree(record.getResponseBody());
        return new IdempotentResult(record.getResponseStatus(), record.getResponseBody(),
                UUID.fromString(body.get("id").asString()), body.get("version").asLong(), true);
    }

    /** Polls for a committed, completed record until {@code maxWait} has passed. */
    private Optional<IdempotencyRecordEntity> awaitWinner(String callerId, String key, Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            Optional<IdempotencyRecordEntity> found = tx.find(callerId, key)
                    .filter(r -> r.getResponseStatus() != IdempotencyRecordEntity.IN_PROGRESS);
            if (found.isPresent() || System.nanoTime() >= deadline) {
                return found;
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    private static boolean namesIdempotencyRecord(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        return message.contains("idempotency_record");
    }

    /** SHA-256 over the command's canonical form (UTF-8), as lower-case hex. */
    static String hash(IdempotentCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(command.canonicalForm().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
