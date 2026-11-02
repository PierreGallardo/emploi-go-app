package architecture.fixtures.subjects.application.first;

import architecture.fixtures.subjects.application.Hub;
import architecture.fixtures.subjects.application.second.SecondFixtures;

public final class FirstFixtures {
    public static class Allowed {}

    public static class DependsOnSecond {
        SecondFixtures.DependsOnFirst second;
    }

    public static class DependsOnHub {
        Hub hub;
    }
}
