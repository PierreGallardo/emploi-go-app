package fr.emploigo.establishment.application.search;

import fr.emploigo.config.ApiOnly;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Divia stops of the map and the dates of the open data it shows. */
@Service
@ApiOnly
public class ReadDatasetsUseCase {
    private final DatasetsRepository datasets;

    public ReadDatasetsUseCase(DatasetsRepository datasets) {
        this.datasets = datasets;
    }

    @Transactional(readOnly = true)
    public Datasets read() {
        return datasets.read();
    }
}
