package fr.emploigo.establishment.application.search;

import fr.emploigo.config.ApiOnly;
import fr.emploigo.establishment.domain.Siret;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The page of a published establishment. */
@Service
@ApiOnly
public class ReadEstablishmentUseCase {
    private final PublishedEstablishmentRepository establishments;

    public ReadEstablishmentUseCase(PublishedEstablishmentRepository establishments) {
        this.establishments = establishments;
    }

    /** Throws {@link EstablishmentNotFoundException} for an establishment the map does not show. */
    @Transactional(readOnly = true)
    public EstablishmentDetail read(Siret siret) {
        return establishments
                .findDetail(siret)
                .map(EstablishmentDetail::shown)
                .orElseThrow(EstablishmentNotFoundException::new);
    }
}
