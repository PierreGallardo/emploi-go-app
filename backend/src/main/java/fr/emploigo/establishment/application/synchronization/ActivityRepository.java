package fr.emploigo.establishment.application.synchronization;

import fr.emploigo.establishment.domain.ActivityCode;

import java.util.Set;

/** The NAF sub-classes and our classification of them into sectors. */
public interface ActivityRepository {
    /** The sub-classes that have a sector: the allow list of the import. */
    Set<ActivityCode> findClassified();
}
