package fr.emploigo.auth.application.google;

import fr.emploigo.auth.domain.SessionIdentity;
import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;

import java.util.UUID;

/** Reads the step that a pending Google flow of this browser has reached. */
@Service
@ApiOnly
public class ReadGoogleStageUseCase {
    private final GoogleTransactions transactions;

    public ReadGoogleStageUseCase(GoogleTransactions transactions) {
        this.transactions = transactions;
    }

    public GoogleTransactions.Stage read(UUID id, String proof, SessionIdentity current) {
        return transactions.stage(id, proof, current);
    }
}
