package fr.emploigo.auth.infrastructure;

import java.net.URI;
import java.util.Set;

/**
 * The checks of the addresses of the providers: https, or http on a loopback host under a local
 * profile.
 */
public final class ProviderEndpoints {
    private ProviderEndpoints() {}

    public static void validateOrigin(URI uri, boolean local) {
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || !("https".equals(uri.getScheme())
                        || local && "http".equals(uri.getScheme()) && isLoopback(uri)))
            throw new IllegalStateException("Origine de confirmation invalide.");
    }

    public static boolean isLoopback(URI uri) {
        return Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
    }
}
