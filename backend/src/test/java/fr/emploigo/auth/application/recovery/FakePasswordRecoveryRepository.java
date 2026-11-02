package fr.emploigo.auth.application.recovery;

import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The resets in memory, with the rules of the table: a new request replaces the pending resets of
 * its account. The reconnections and the sessions that a change revokes are not modelled here.
 */
public final class FakePasswordRecoveryRepository implements PasswordRecoveryRepository {
    private final Map<UUID, Reset> resets = new HashMap<>();

    /** Stores a reset that already exists, as a fixture. */
    public void store(Reset reset) {
        resets.put(reset.id(), reset);
    }

    @Override
    public Reset reset(UUID id) {
        return resets.get(id);
    }

    @Override
    public void replace(long accountId) {
        resets.replaceAll(
                (id, reset) ->
                        reset.accountId() == accountId && reset.status().equals("PENDING")
                                ? new Reset(
                                        reset.id(),
                                        reset.accountId(),
                                        reset.hash(),
                                        reset.keyId(),
                                        reset.version(),
                                        reset.expiresAt(),
                                        "REPLACED",
                                        null)
                                : reset);
    }

    @Override
    public void insert(
            UUID id,
            long accountId,
            long version,
            String hash,
            String keyId,
            Instant expiry) {
        if (resets.containsKey(id))
            throw new DuplicateKeyException("Réinitialisation déjà enregistrée");
        resets.put(id, new Reset(id, accountId, hash, keyId, version, expiry, "PENDING", null));
    }

    @Override
    public boolean consume(Reset reset, Instant now) {
        var current = resets.get(reset.id());
        if (current == null || !current.status().equals("PENDING")) return false;
        resets.put(
                reset.id(),
                new Reset(
                        reset.id(),
                        reset.accountId(),
                        reset.hash(),
                        reset.keyId(),
                        reset.version(),
                        reset.expiresAt(),
                        "USED",
                        now));
        return true;
    }
}
