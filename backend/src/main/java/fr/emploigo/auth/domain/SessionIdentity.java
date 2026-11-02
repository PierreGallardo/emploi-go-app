package fr.emploigo.auth.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record SessionIdentity(
        long accountId, long credentialVersion, UUID familyId, Instant startedAt, boolean explicit)
        implements Serializable {
    public static final String ATTRIBUTE = "auth.identity";
}
