package fr.emploigo.auth.application.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Records, in order, the browser proofs whose principal the session has changed. */
public final class FakeBrowserProofRepository implements BrowserProofRepository {
    public record Change(String browserHash, UUID retainedFamily) {}

    private final List<Change> changes = new ArrayList<>();

    @Override
    public void principalChanged(String browserHash, UUID retainedFamily) {
        changes.add(new Change(browserHash, retainedFamily));
    }

    public List<Change> changes() {
        return List.copyOf(changes);
    }
}
