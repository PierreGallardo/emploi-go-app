package fr.emploigo.establishment.application.search;

import java.util.ArrayList;
import java.util.List;

/** The jobs given, found whatever the text; records the texts asked for. */
public final class FakeJobRepository implements JobRepository {
    private final List<JobMatch> jobs = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();

    public FakeJobRepository add(JobMatch job) {
        jobs.add(job);
        return this;
    }

    public List<String> asked() {
        return asked;
    }

    @Override
    public List<JobMatch> findPublished(String text, int limit) {
        asked.add(text);
        return jobs.stream().limit(limit).toList();
    }
}
