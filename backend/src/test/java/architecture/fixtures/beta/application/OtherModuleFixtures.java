package architecture.fixtures.beta.application;

import architecture.fixtures.alpha.api.ApiFixtures;
import architecture.fixtures.alpha.application.ApplicationFixtures;
import architecture.fixtures.alpha.application.internal.InternalFacade;
import architecture.fixtures.alpha.domain.DomainFixtures;
import architecture.fixtures.alpha.infrastructure.InfrastructureFixtures;

public final class OtherModuleFixtures {
    public static class Allowed {
        ApplicationFixtures.Allowed publicUseCase;
    }

    public static class WithForeignDomain {
        DomainFixtures.MappedEntity value;
    }

    public static class WithForeignInfrastructure {
        InfrastructureFixtures.Allowed adapter;
    }

    public static class WithForeignApi {
        ApiFixtures.Allowed controller;
    }

    public static class WithInternalApplication {
        InternalFacade internal;
    }

    public static class Cycle {
        ApplicationFixtures.Cycle other;
    }
}
