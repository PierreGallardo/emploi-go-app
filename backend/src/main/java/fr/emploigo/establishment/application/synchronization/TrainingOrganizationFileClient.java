package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Siret;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** The public list of the training organisations, published by the Ministry of Labour. */
public interface TrainingOrganizationFileClient {
    /**
     * The establishments that declared themselves training organisations, unless the list was not
     * published after {@code knownPublication}.
     */
    Result fetch(Optional<Instant> knownPublication);

    sealed interface Result {
        record Published(Instant publishedAt, Set<Siret> sirets) implements Result {
            public Published {
                sirets = Set.copyOf(sirets);
            }
        }

        record Unchanged() implements Result {}

        /** No answer, or a file the client cannot read whole. */
        record Unavailable(String cause) implements Result {}
    }
}
