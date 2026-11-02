package architecture.fixtures.subjects.application.second;

import architecture.fixtures.subjects.application.first.FirstFixtures;

public final class SecondFixtures {
    public static class Allowed {}

    public static class DependsOnFirst {
        FirstFixtures.Allowed first;
    }
}
