package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;

import org.springframework.data.jpa.repository.JpaRepository;

@WorkerOnly
interface SpringDataDatasetPublicationRepository
        extends JpaRepository<DatasetPublicationJpaEntity, DatasetPublicationJpaEntity.Dataset> {}
