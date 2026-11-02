package fr.emploigo.establishment.application.synchronization;

import java.util.List;

/** The communes of Dijon Métropole, where the establishments are imported from. */
public interface CommuneRepository {
    /** Their INSEE codes. */
    List<String> findCodes();
}
