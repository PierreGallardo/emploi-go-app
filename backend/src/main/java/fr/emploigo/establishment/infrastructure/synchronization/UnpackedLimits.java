package fr.emploigo.establishment.infrastructure.synchronization;

/**
 * What a downloaded file may unpack to, so that a forged or broken file cannot fill the memory of
 * the worker: bytes over one reading of the file, whatever its entries, characters of a line, and
 * lines of a table.
 */
record UnpackedLimits(long maxBytes, int maxLineLength, int maxRecords) {
    /** About ten times the Divia feed and the addresses of the Côte-d'Or, unpacked. */
    static final UnpackedLimits OPEN_DATA =
            new UnpackedLimits(512L * 1024 * 1024, 64 * 1024, 5_000_000);
}
