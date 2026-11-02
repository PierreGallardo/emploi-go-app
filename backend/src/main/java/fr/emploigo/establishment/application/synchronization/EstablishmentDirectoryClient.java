package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.DirectoryEstablishment;

import java.util.List;

/** The Sirene directory of the State (INSEE), which lists establishments. */
public interface EstablishmentDirectoryClient {
    /**
     * One page of the open employer establishments of these communes, after {@code cursor} (null
     * for the first page), waiting as long as the rate limit of the directory asks.
     */
    Result read(List<String> communeCodes, String cursor);

    sealed interface Result {
        /**
         * The establishments the client could read; {@code total} is what the directory announces
         * for the whole search, and {@code nextCursor} is null on the last page.
         */
        record Page(int total, String nextCursor, List<DirectoryEstablishment> establishments)
                implements Result {
            public Page {
                establishments = List.copyOf(establishments);
            }
        }

        /** No answer after the retries, or an answer the client cannot read. */
        record Unavailable(String cause) implements Result {}
    }
}
