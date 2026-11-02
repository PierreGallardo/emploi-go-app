CREATE SCHEMA reference;

-- The centre of a commune (geo.api.gouv.fr, Licence Ouverte 2.0) is where « Où ? » takes a youth.
CREATE TABLE reference.communes (
    code VARCHAR(5) PRIMARY KEY CHECK (code ~ '^[0-9]{5}$'),
    name VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90)
);

-- The 19 sectors of the map.
CREATE TABLE reference.sectors (
    code VARCHAR(32) PRIMARY KEY CHECK (code ~ '^[A-Z_]+$')
);

INSERT INTO reference.sectors (code)
VALUES
    ('AGRICULTURE'),
    ('FOOD'),
    ('INDUSTRY'),
    ('ENERGY_ENVIRONMENT'),
    ('CONSTRUCTION'),
    ('AUTOMOTIVE'),
    ('RETAIL'),
    ('TRANSPORT_LOGISTICS'),
    ('HOSPITALITY_TOURISM'),
    ('IT_DIGITAL'),
    ('MEDIA_COMMUNICATION'),
    ('FINANCE_REAL_ESTATE'),
    ('BUSINESS_SERVICES'),
    ('EDUCATION_TRAINING'),
    ('HEALTH'),
    ('SOCIAL_PERSONAL_SERVICES'),
    ('BEAUTY_WELLNESS'),
    ('CULTURE_SPORT_LEISURE'),
    ('PUBLIC_SERVICE');

CREATE TABLE reference.naf_divisions (
    nomenclature VARCHAR(8) NOT NULL CHECK (nomenclature IN ('NAF_REV2', 'NAF_2025')),
    code VARCHAR(2) NOT NULL CHECK (code ~ '^[0-9]{2}$'),
    sector_code VARCHAR(32) REFERENCES reference.sectors (code),
    PRIMARY KEY (nomenclature, code)
);

-- A sub-class without sector is not imported: the table is the allow list of the import.
CREATE TABLE reference.naf_activities (
    nomenclature VARCHAR(8) NOT NULL,
    code VARCHAR(6) NOT NULL CHECK (code ~ '^[0-9]{2}\.[0-9]{2}[A-Z]$'),
    label VARCHAR(255) NOT NULL CHECK (length(btrim(label)) > 0),
    -- A searched text is stored in its search form beside it: a search reads it on every row.
    label_search TEXT GENERATED ALWAYS AS (search_text(label)) STORED,
    division_code VARCHAR(2) GENERATED ALWAYS AS (left(code, 2)) STORED,
    sector_code VARCHAR(32) REFERENCES reference.sectors (code),
    PRIMARY KEY (nomenclature, code),
    FOREIGN KEY (nomenclature, division_code) REFERENCES reference.naf_divisions (nomenclature, code)
);

CREATE TABLE reference.naf_correspondences (
    rev2_code VARCHAR(6) NOT NULL,
    rev2_nomenclature VARCHAR(8) GENERATED ALWAYS AS ('NAF_REV2') STORED,
    naf2025_code VARCHAR(6) NOT NULL,
    naf2025_nomenclature VARCHAR(8) GENERATED ALWAYS AS ('NAF_2025') STORED,
    PRIMARY KEY (rev2_code, naf2025_code),
    FOREIGN KEY (rev2_nomenclature, rev2_code) REFERENCES reference.naf_activities (nomenclature, code),
    FOREIGN KEY (naf2025_nomenclature, naf2025_code) REFERENCES reference.naf_activities (nomenclature, code)
);

-- The map searches a job by the label the youth picked: a label names one job only.
CREATE TABLE reference.jobs (
    rome_code VARCHAR(5) PRIMARY KEY CHECK (rome_code ~ '^[A-N][0-9]{4}$'),
    label VARCHAR(255) NOT NULL UNIQUE CHECK (length(btrim(label)) > 0),
    label_search TEXT GENERATED ALWAYS AS (search_text(label)) STORED
);

-- France Travail links a job to whole NAF divisions, whatever the nomenclature: they are read in NAF rév. 2.
CREATE TABLE reference.job_divisions (
    rome_code VARCHAR(5) NOT NULL REFERENCES reference.jobs (rome_code),
    division_nomenclature VARCHAR(8) GENERATED ALWAYS AS ('NAF_REV2') STORED,
    division VARCHAR(2) NOT NULL,
    PRIMARY KEY (rome_code, division),
    FOREIGN KEY (division_nomenclature, division) REFERENCES reference.naf_divisions (nomenclature, code)
);

-- Replaces one division of a job by the NAF rév. 2 sub-classes where the job is really done. A newer
-- France Travail table that drops the division fails on the key, to be refined again by hand.
CREATE TABLE reference.job_division_refinements (
    rome_code VARCHAR(5) NOT NULL,
    division VARCHAR(2) NOT NULL,
    activity_nomenclature VARCHAR(8) GENERATED ALWAYS AS ('NAF_REV2') STORED,
    activity_code VARCHAR(6) NOT NULL,
    PRIMARY KEY (rome_code, division, activity_code),
    FOREIGN KEY (rome_code, division) REFERENCES reference.job_divisions (rome_code, division),
    FOREIGN KEY (activity_nomenclature, activity_code)
        REFERENCES reference.naf_activities (nomenclature, code)
);

-- The one correspondence between jobs and activities, read both ways (job → establishments,
-- establishment → jobs). NAF rév. 2: the refined sub-classes of a division, otherwise the
-- sub-classes of the division that share its sector, so a job is never sent to the exceptions of
-- its division. NAF 2025: the official successors of these sub-classes that keep their sector.
-- Materialized, as each search by job reads it: every migration that changes the jobs, the
-- activities or their sectors ends with `REFRESH MATERIALIZED VIEW reference.job_activities`.
CREATE MATERIALIZED VIEW reference.job_activities AS
WITH rev2 AS NOT MATERIALIZED (
    SELECT rome_code, activity_code
    FROM reference.job_division_refinements
    UNION
    SELECT j.rome_code, a.code
    FROM reference.job_divisions j
    JOIN reference.naf_divisions d ON d.nomenclature = 'NAF_REV2' AND d.code = j.division
    JOIN reference.naf_activities a
        ON a.nomenclature = d.nomenclature
        AND a.division_code = d.code
        AND a.sector_code = d.sector_code
    WHERE NOT EXISTS (
        SELECT 1
        FROM reference.job_division_refinements r
        WHERE r.rome_code = j.rome_code AND r.division = j.division
    )
)
SELECT rome_code, 'NAF_REV2'::VARCHAR(8) AS nomenclature, activity_code
FROM rev2
UNION
SELECT rev2.rome_code, 'NAF_2025'::VARCHAR(8), c.naf2025_code
FROM rev2
JOIN reference.naf_activities rev2_activity
    ON rev2_activity.nomenclature = 'NAF_REV2' AND rev2_activity.code = rev2.activity_code
JOIN reference.naf_correspondences c ON c.rev2_code = rev2.activity_code
JOIN reference.naf_activities successor
    ON successor.nomenclature = 'NAF_2025'
    AND successor.code = c.naf2025_code
    AND successor.sector_code = rev2_activity.sector_code
WITH NO DATA;

CREATE UNIQUE INDEX job_activities_job_idx
    ON reference.job_activities (rome_code, nomenclature, activity_code);
CREATE INDEX job_activities_activity_idx ON reference.job_activities (nomenclature, activity_code);

INSERT INTO reference.communes (code, name, longitude, latitude)
VALUES
    ('21003', 'Ahuy', 5.0179, 47.3696),
    ('21105', 'Bressey-sur-Tille', 5.1827, 47.3082),
    ('21106', 'Bretenière', 5.1016, 47.2374),
    ('21166', 'Chenôve', 5.0001, 47.2911),
    ('21171', 'Chevigny-Saint-Sauveur', 5.132, 47.2986),
    ('21192', 'Corcelles-les-Monts', 4.9452, 47.3033),
    ('21223', 'Daix', 4.9729, 47.3635),
    ('21231', 'Dijon', 5.0322, 47.3319),
    ('21263', 'Fénay', 5.054, 47.2452),
    ('21270', 'Flavignerot', 4.9173, 47.2786),
    ('21278', 'Fontaine-lès-Dijon', 5.025, 47.3477),
    ('21315', 'Hauteville-lès-Dijon', 4.9781, 47.3783),
    ('21355', 'Longvic', 5.0585, 47.2855),
    ('21370', 'Magny-sur-Tille', 5.1691, 47.2747),
    ('21390', 'Marsannay-la-Côte', 4.9875, 47.2803),
    ('21452', 'Neuilly-Crimolois', 5.1143, 47.2723),
    ('21473', 'Ouges', 5.0775, 47.2589),
    ('21481', 'Perrigny-lès-Dijon', 5.0197, 47.2556),
    ('21485', 'Plombières-lès-Dijon', 4.9473, 47.3368),
    ('21515', 'Quetigny', 5.1123, 47.3209),
    ('21540', 'Saint-Apollinaire', 5.0939, 47.3367),
    ('21605', 'Sennecey-lès-Dijon', 5.1061, 47.2922),
    ('21617', 'Talant', 4.9959, 47.3389);

ANALYZE reference.communes, reference.sectors;
