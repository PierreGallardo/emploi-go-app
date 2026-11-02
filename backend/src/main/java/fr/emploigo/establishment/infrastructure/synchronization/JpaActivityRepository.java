package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.ActivityRepository;
import fr.emploigo.establishment.domain.ActivityCode;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Repository
@WorkerOnly
class JpaActivityRepository implements ActivityRepository {
    private final SpringDataActivityRepository activities;

    JpaActivityRepository(SpringDataActivityRepository activities) {
        this.activities = activities;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<ActivityCode> findClassified() {
        return activities.findBySectorCodeIsNotNull().stream()
                .map(NafActivityJpaEntity::toDomain)
                .collect(Collectors.toUnmodifiableSet());
    }
}
