-- The State data are rewritten by the import; the last columns are ours and the import never
-- touches them. An excluded establishment keeps its last data, hidden, until the purge.
-- The directory lists the open employers only: an establishment that closes or stops employing is
-- absent from a complete run (NOT_FOUND).
CREATE TABLE establishments (
    siret VARCHAR(14) PRIMARY KEY CHECK (siret ~ '^[0-9]{14}$'),
    name VARCHAR(255) NOT NULL CHECK (length(btrim(name)) > 0),
    company_name VARCHAR(255) NOT NULL CHECK (length(btrim(company_name)) > 0),
    name_search TEXT GENERATED ALWAYS AS (search_text(name)) STORED,
    company_search TEXT GENERATED ALWAYS AS (search_text(company_name)) STORED,
    address VARCHAR(500) NOT NULL CHECK (length(btrim(address)) > 0),
    commune_code VARCHAR(5) NOT NULL REFERENCES reference.communes (code),
    longitude DOUBLE PRECISION CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION CHECK (latitude BETWEEN -90 AND 90),
    location GEOGRAPHY(POINT, 4326)
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::GEOGRAPHY) STORED,
    activity_nomenclature VARCHAR(8) NOT NULL,
    activity_code VARCHAR(6) NOT NULL,
    company_type VARCHAR(16) NOT NULL CHECK (company_type IN (
        'COMPANY', 'TEMP_AGENCY', 'RECRUITMENT', 'TRAINING', 'IT_SERVICES')),
    headcount_range VARCHAR(2) CHECK (headcount_range IN (
        '00', '01', '02', '03', '11', '12', '21', '22', '31', '32', '41', '42', '51', '52', '53')),
    size VARCHAR(6) GENERATED ALWAYS AS (CASE
        WHEN headcount_range IN ('00', '01', '02', '03') THEN 'SMALL'
        WHEN headcount_range IN ('11', '12', '21', '22', '31') THEN 'MEDIUM'
        WHEN headcount_range IS NOT NULL THEN 'LARGE'
    END) STORED,
    company_category VARCHAR(3) CHECK (company_category IN ('PME', 'ETI', 'GE')),
    company_created_on DATE,
    ess BOOLEAN NOT NULL,
    exclusion_reason VARCHAR(17) CHECK (exclusion_reason IN (
        'EXCLUDED_ACTIVITY', 'NO_SIGN', 'INCOMPLETE', 'NOT_FOUND')),
    excluded_at TIMESTAMPTZ,
    seen_at TIMESTAMPTZ NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(2000) CHECK (length(btrim(description)) > 0),
    image_file VARCHAR(100) CHECK (image_file ~ '^[A-Za-z0-9_-]+\.(webp|png|jpe?g)$'),
    jobs_url VARCHAR(2048) CHECK (jobs_url ~ '^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[^[:space:][:cntrl:]"<>\\]*)?$'),
    CONSTRAINT establishments_coordinates_check CHECK ((longitude IS NULL) = (latitude IS NULL)),
    CONSTRAINT establishments_exclusion_check CHECK ((exclusion_reason IS NULL) = (excluded_at IS NULL)),
    FOREIGN KEY (activity_nomenclature, activity_code)
        REFERENCES reference.naf_activities (nomenclature, code)
);

CREATE INDEX establishments_location_idx ON establishments USING GIST (location);
CREATE INDEX establishments_activity_idx ON establishments (activity_nomenclature, activity_code);
CREATE INDEX establishments_purge_idx ON establishments (excluded_at) WHERE excluded_at IS NOT NULL;
