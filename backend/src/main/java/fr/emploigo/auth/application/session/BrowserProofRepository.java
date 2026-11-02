package fr.emploigo.auth.application.session;

import java.util.UUID;

public interface BrowserProofRepository {
    void principalChanged(String browserHash, UUID retainedFamily);
}
