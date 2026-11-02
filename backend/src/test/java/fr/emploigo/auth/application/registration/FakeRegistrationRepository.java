package fr.emploigo.auth.application.registration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registrations and codes in memory, with the rules of the database that the use cases rely on: the
 * codes are read latest first, and a row is changed in place. The row lock changes nothing.
 */
public final class FakeRegistrationRepository implements RegistrationRepository {
    private final List<RegistrationRow> registrations = new ArrayList<>();
    private final List<VerificationRow> verifications = new ArrayList<>();

    @Override
    public Pending lockById(UUID id) {
        return registrationRow(id).map(RegistrationRow::pending).orElse(null);
    }

    @Override
    public Pending byEmail(String email) {
        return registrations.stream()
                .filter(row -> row.status.equals("PENDING") && email.equals(row.emailKey))
                .findFirst()
                .map(RegistrationRow::pending)
                .orElse(null);
    }

    @Override
    public void expireConflicts(String email, Instant now) {
        registrations.stream()
                .filter(row -> row.status.equals("PENDING"))
                .filter(row -> !row.expiresAt.isAfter(now))
                .filter(row -> email.equals(row.emailKey))
                .forEach(RegistrationRow::expire);
    }

    @Override
    public void expirePending(String email) {
        registrations.stream()
                .filter(row -> row.status.equals("PENDING"))
                .filter(row -> email.equals(row.emailKey))
                .forEach(RegistrationRow::expire);
    }

    @Override
    public void insert(
            UUID id,
            String email,
            String passwordHash,
            String confirmationKeyHash,
            Instant expiresAt) {
        var row = new RegistrationRow(id);
        row.emailKey = email;
        row.passwordHash = passwordHash;
        row.confirmationKeyHash = confirmationKeyHash;
        row.expiresAt = expiresAt;
        registrations.add(row);
    }

    @Override
    public void restart(UUID id, String passwordHash, String confirmationKeyHash) {
        registrationRow(id)
                .ifPresent(
                        row -> {
                            row.passwordHash = passwordHash;
                            row.confirmationKeyHash = confirmationKeyHash;
                        });
    }

    @Override
    public void replaceVerification(UUID registrationId) {
        verifications.stream()
                .filter(row -> row.registrationId.equals(registrationId))
                .filter(row -> row.status.equals("PENDING"))
                .forEach(row -> row.status = "REPLACED");
    }

    @Override
    public void addVerification(
            UUID id,
            UUID registrationId,
            String hash,
            String keyId,
            Instant now,
            Instant expiresAt) {
        var row = new VerificationRow(id, registrationId);
        row.tokenHash = hash;
        row.keyId = keyId;
        row.expiresAt = expiresAt;
        verifications.add(row);
    }

    @Override
    public void extend(UUID registrationId, Instant expiresAt) {
        registrationRow(registrationId).ifPresent(row -> row.expiresAt = expiresAt);
    }

    @Override
    public List<Verification> verifications(UUID registrationId) {
        var latestFirst = new ArrayList<>(verifications);
        Collections.reverse(latestFirst);
        return latestFirst.stream()
                .filter(row -> row.registrationId.equals(registrationId))
                .limit(20)
                .map(VerificationRow::verification)
                .toList();
    }

    @Override
    public int recordFailure(UUID verificationId, UUID registrationId) {
        verificationRow(verificationId).ifPresent(row -> row.failedAttempts++);
        return registrationRow(registrationId)
                .map(
                        row -> {
                            row.codeFailures++;
                            return row.codeFailures;
                        })
                .orElseThrow();
    }

    @Override
    public void blockCodes(UUID verificationId, UUID registrationId, Instant until, int attempts) {
        verificationRow(verificationId).ifPresent(row -> row.failedAttempts = attempts);
        registrationRow(registrationId).ifPresent(row -> row.codesBlockedUntil = until);
    }

    @Override
    public Verification verification(UUID id) {
        return verificationRow(id).map(VerificationRow::verification).orElse(null);
    }

    @Override
    public void consume(UUID operation, UUID registrationId, long accountId) {
        verificationRow(operation).ifPresent(row -> row.status = "USED");
        registrationRow(registrationId)
                .ifPresent(
                        row -> {
                            row.status = "CONFIRMED";
                            row.emailKey = null;
                            row.passwordHash = null;
                        });
    }

    private Optional<RegistrationRow> registrationRow(UUID id) {
        return registrations.stream().filter(row -> row.id.equals(id)).findFirst();
    }

    private Optional<VerificationRow> verificationRow(UUID id) {
        return verifications.stream().filter(row -> row.id.equals(id)).findFirst();
    }

    private static final class RegistrationRow {
        private final UUID id;
        private String emailKey;
        private String passwordHash;
        private String confirmationKeyHash;
        private int codeFailures;
        private Instant codesBlockedUntil;
        private Instant expiresAt;
        private String status = "PENDING";

        private RegistrationRow(UUID id) {
            this.id = id;
        }

        private Pending pending() {
            return new Pending(
                    id,
                    emailKey,
                    passwordHash,
                    confirmationKeyHash,
                    codeFailures,
                    codesBlockedUntil,
                    expiresAt,
                    status);
        }

        private void expire() {
            status = "EXPIRED";
            emailKey = null;
            passwordHash = null;
            confirmationKeyHash = null;
        }
    }

    private static final class VerificationRow {
        private final UUID id;
        private final UUID registrationId;
        private String tokenHash;
        private String keyId;
        private Instant expiresAt;
        private String status = "PENDING";
        private int failedAttempts;

        private VerificationRow(UUID id, UUID registrationId) {
            this.id = id;
            this.registrationId = registrationId;
        }

        private Verification verification() {
            return new Verification(
                    id, registrationId, tokenHash, keyId, expiresAt, status, failedAttempts);
        }
    }
}
