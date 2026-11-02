package fr.emploigo.establishment.application.search;

/** The copies of the open data files that the worker imports. */
public interface DatasetsRepository {
    Datasets read();
}
