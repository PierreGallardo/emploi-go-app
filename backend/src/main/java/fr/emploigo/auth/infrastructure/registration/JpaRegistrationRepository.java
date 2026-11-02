package fr.emploigo.auth.infrastructure.registration;

import fr.emploigo.auth.application.registration.RegistrationRepository;
import fr.emploigo.config.ApiOnly;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@ApiOnly
public class JpaRegistrationRepository implements RegistrationRepository {
    private static final String PENDING = "PENDING";
    private final SpringDataRegistrationRepository registrationRows;
    private final SpringDataRegistrationVerificationRepository verificationRows;
    private final EntityManager entities;

    public JpaRegistrationRepository(
            SpringDataRegistrationRepository registrationRows,
            SpringDataRegistrationVerificationRepository verificationRows,
            EntityManager entities) {
        this.registrationRows = registrationRows;
        this.verificationRows = verificationRows;
        this.entities = entities;
    }

    @Override
    public RegistrationRepository.Pending lockById(UUID id) {
        return registrationRows.selectByIdForUpdate(id).map(this::detachedPending).orElse(null);
    }

    @Override
    public RegistrationRepository.Pending byEmail(String email) {
        return registrationRows
                .findFirstByEmailKeyAndStatus(email, PENDING)
                .map(this::detachedPending)
                .orElse(null);
    }

    @Override
    public void expireConflicts(String email, Instant now) {
        registrationRows.expireConflicts(email, now);
    }

    @Override
    public void expirePending(String email) {
        registrationRows.expirePending(email);
    }

    @Override
    public void insert(
            UUID id,
            String email,
            String passwordHash,
            String confirmationKeyHash,
            Instant expiresAt) {
        var row =
                registrationRows.saveAndFlush(
                        RegistrationJpaEntity.pending(
                                id, email, passwordHash, confirmationKeyHash, expiresAt));
        entities.detach(row);
    }

    @Override
    public void restart(UUID id, String passwordHash, String confirmationKeyHash) {
        changeRegistration(id, row -> row.restart(passwordHash, confirmationKeyHash));
    }

    @Override
    public void replaceVerification(UUID registrationId) {
        verificationRows.replaceVerification(registrationId);
    }

    @Override
    public void addVerification(
            UUID id,
            UUID registrationId,
            String hash,
            String keyId,
            Instant now,
            Instant expiresAt) {
        var row =
                verificationRows.saveAndFlush(
                        RegistrationVerificationJpaEntity.issued(
                                id, registrationId, hash, keyId, now, expiresAt));
        entities.detach(row);
    }

    @Override
    public void extend(UUID registrationId, Instant expiresAt) {
        changeRegistration(registrationId, row -> row.extend(expiresAt));
    }

    @Override
    public List<RegistrationRepository.Verification> verifications(UUID registrationId) {
        return verificationRows
                .findTop20ByRegistrationIdOrderByCreatedAtDescIdAsc(registrationId)
                .stream()
                .map(this::detachedVerification)
                .toList();
    }

    @Override
    public int recordFailure(UUID verificationId, UUID registrationId) {
        verificationRows.incrementFailedAttempts(verificationId);
        return registrationRows.incrementCodeFailures(registrationId);
    }

    @Override
    public void blockCodes(UUID verificationId, UUID registrationId, Instant until, int attempts) {
        changeVerification(verificationId, row -> row.spendAttempts(attempts));
        changeRegistration(registrationId, row -> row.blockCodesUntil(until));
    }

    @Override
    public RegistrationRepository.Verification verification(UUID id) {
        return verificationRows.findById(id).map(this::detachedVerification).orElse(null);
    }

    @Override
    public void consume(UUID operation, UUID registration, long accountId) {
        changeVerification(operation, RegistrationVerificationJpaEntity::markUsed);
        changeRegistration(registration, row -> row.consume(accountId));
    }

    /**
     * Writes the change of a row read again under the lock of the use case, then detaches it: a
     * managed copy would be returned by the next locked read of the transaction.
     */
    private void changeRegistration(UUID id, Consumer<RegistrationJpaEntity> change) {
        registrationRows
                .findById(id)
                .ifPresent(
                        row -> {
                            change.accept(row);
                            registrationRows.saveAndFlush(row);
                            entities.detach(row);
                        });
    }

    private void changeVerification(
            UUID id, Consumer<RegistrationVerificationJpaEntity> change) {
        verificationRows
                .findById(id)
                .ifPresent(
                        row -> {
                            change.accept(row);
                            verificationRows.saveAndFlush(row);
                            entities.detach(row);
                        });
    }

    /** Detaches the row before its copy is returned, as the locked reads of the use case need. */
    private RegistrationRepository.Pending detachedPending(RegistrationJpaEntity row) {
        entities.detach(row);
        return row.toPending();
    }

    private RegistrationRepository.Verification detachedVerification(
            RegistrationVerificationJpaEntity row) {
        entities.detach(row);
        return row.toVerification();
    }
}
