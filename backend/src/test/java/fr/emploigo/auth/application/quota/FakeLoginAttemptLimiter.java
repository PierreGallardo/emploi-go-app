package fr.emploigo.auth.application.quota;

import java.util.ArrayList;
import java.util.List;

/**
 * The login counters in memory: an attempt is admitted unless the test chose another admission,
 * and each identity forgotten by a success or a recovered password is kept.
 */
public final class FakeLoginAttemptLimiter implements LoginAttemptLimiter {
    private final List<String> begun = new ArrayList<>();
    private final List<String> forgotten = new ArrayList<>();
    private Admission admission;

    public void answer(Admission admission) {
        this.admission = admission;
    }

    /** The identities of the attempts begun, in order. */
    public List<String> begun() {
        return List.copyOf(begun);
    }

    /** The identities forgotten by a success or a recovered password, in order. */
    public List<String> forgotten() {
        return List.copyOf(forgotten);
    }

    @Override
    public Admission begin(String identity, String address, LoginLimits limits) {
        begun.add(identity);
        return admission != null ? admission : new Admitted();
    }

    @Override
    public boolean forget(String identity) {
        forgotten.add(identity);
        return true;
    }
}
