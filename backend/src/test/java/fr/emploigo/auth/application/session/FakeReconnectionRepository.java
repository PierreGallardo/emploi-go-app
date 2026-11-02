package fr.emploigo.auth.application.session;

import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reconnection families in memory, with the rules of the rows. The locks change nothing. */
public final class FakeReconnectionRepository implements ReconnectionRepository {
    private final Map<UUID, Reconnection> families = new HashMap<>();
    private int lookups;

    /** Stores a family as it is, as a fixture. */
    public void store(Reconnection row) {
        families.put(row.id(), row);
    }

    /** The number of reads of a family: a cookie refused before the lookup reads none. */
    public int lookups() {
        return lookups;
    }

    @Override
    public void insert(
            UUID id,
            long accountId,
            long version,
            String hash,
            Instant expiresAt,
            Instant issuedAt) {
        if (families.containsKey(id))
            throw new DuplicateKeyException("Famille déjà enregistrée");
        store(new Reconnection(id, accountId, version, hash, null, expiresAt, issuedAt, false));
    }

    @Override
    public Reconnection lock(UUID id) {
        return find(id);
    }

    @Override
    public Reconnection find(UUID id) {
        lookups++;
        return families.get(id);
    }

    @Override
    public void rotate(Reconnection previous, String hash, Instant expiresAt, Instant issuedAt) {
        var row = families.get(previous.id());
        if (row != null)
            store(
                    new Reconnection(
                            row.id(),
                            row.accountId(),
                            row.credentialVersion(),
                            hash,
                            row.secretHash(),
                            expiresAt,
                            issuedAt,
                            row.revoked()));
    }

    @Override
    public boolean active(UUID id, long accountId, long version, Instant now) {
        var row = families.get(id);
        return row != null
                && row.accountId() == accountId
                && row.credentialVersion() == version
                && !row.revoked()
                && row.expiresAt().isAfter(now);
    }

    @Override
    public void revoke(UUID id) {
        var row = families.get(id);
        if (row != null) store(revoked(row));
    }

    @Override
    public boolean revokeReplayed(UUID id, String previousHash, Instant rotatedBefore) {
        var row = families.get(id);
        if (row == null
                || row.revoked()
                || row.previousHash() == null
                || !row.previousHash().equals(previousHash)
                || !row.rotatedAt().isBefore(rotatedBefore)) return false;
        store(revoked(row));
        return true;
    }

    private static Reconnection revoked(Reconnection row) {
        return new Reconnection(
                row.id(),
                row.accountId(),
                row.credentialVersion(),
                row.secretHash(),
                row.previousHash(),
                row.expiresAt(),
                row.rotatedAt(),
                true);
    }
}
