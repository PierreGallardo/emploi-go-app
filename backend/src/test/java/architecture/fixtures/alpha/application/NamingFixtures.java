package architecture.fixtures.alpha.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

public final class NamingFixtures {
    public static class OfferHelper {}

    public static class OfferManager {}

    public static class OfferImpl {}

    public static class OfferInfo {}

    public static class OfferData {}

    public static class OfferService {}

    public interface IOffer {}

    public interface Identity {}

    public static class OfferUseCase {}

    public static class OfferUnavailableException extends RuntimeException {}

    public static class Refusal extends RuntimeException {}

    @ConfigurationProperties("fixture")
    public record Limits(String value) {}
}
