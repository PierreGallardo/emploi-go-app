package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;

import org.springframework.data.repository.Repository;

import java.util.List;

@WorkerOnly
interface SpringDataActivityRepository
        extends Repository<NafActivityJpaEntity, NafActivityJpaEntity.Key> {
    List<NafActivityJpaEntity> findBySectorCodeIsNotNull();
}
