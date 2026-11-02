package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.Address;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The file of the Base Adresse Nationale for the Côte-d'Or, published by the State. */
public interface AddressFileClient {
    /**
     * The addresses of these communes, unless the file was not published after {@code
     * knownPublication}.
     */
    Result fetch(Set<String> communeCodes, Optional<Instant> knownPublication);

    sealed interface Result {
        record Published(Instant publishedAt, List<Address> addresses) implements Result {
            public Published {
                addresses = List.copyOf(addresses);
            }
        }

        record Unchanged() implements Result {}

        /** No answer, or a file the client cannot read whole. */
        record Unavailable(String cause) implements Result {}
    }
}
