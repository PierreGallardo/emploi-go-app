package fr.emploigo.establishment.application.synchronization;

import java.util.ArrayList;
import java.util.List;

/**
 * The directory with the pages given in their order; asked for more pages than it has, it
 * announces nothing. Records the cursors it was asked with.
 */
public final class FakeEstablishmentDirectoryClient implements EstablishmentDirectoryClient {
    private final List<Result> pages = new ArrayList<>();
    private final List<String> cursors = new ArrayList<>();
    private final List<List<String>> communes = new ArrayList<>();
    private int interruptedAt = -1;

    /** Gives this page after the pages already given. */
    public FakeEstablishmentDirectoryClient answer(Result page) {
        pages.add(page);
        return this;
    }

    /** Interrupts the calling thread during this request (from 1), as a stopping worker does. */
    public FakeEstablishmentDirectoryClient interruptAt(int request) {
        interruptedAt = request;
        return this;
    }

    public List<String> cursors() {
        return cursors;
    }

    public List<List<String>> communes() {
        return communes;
    }

    @Override
    public Result read(List<String> communeCodes, String cursor) {
        communes.add(communeCodes);
        cursors.add(cursor);
        int request = cursors.size();
        if (request == interruptedAt) {
            Thread.currentThread().interrupt();
            return new Result.Unavailable("interrompu");
        }
        return request <= pages.size()
                ? pages.get(request - 1)
                : new Result.Page(0, null, List.of());
    }
}
