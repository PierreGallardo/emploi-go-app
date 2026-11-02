package fr.emploigo.establishment.infrastructure.synchronization;

import fr.emploigo.config.WorkerOnly;
import fr.emploigo.establishment.application.synchronization.CommuneRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@WorkerOnly
class JpaCommuneRepository implements CommuneRepository {
    private final SpringDataCommuneRepository communes;

    JpaCommuneRepository(SpringDataCommuneRepository communes) {
        this.communes = communes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findCodes() {
        return communes.findAllByOrderByCode().stream().map(CommuneJpaEntity::code).toList();
    }
}
