-- When each open data set we copy was published, for the mention of its source.
CREATE TABLE dataset_publications (
    dataset VARCHAR(32) PRIMARY KEY CHECK (dataset IN ('TRANSIT_STOPS', 'ADDRESSES', 'TRAINING_ORGANIZATIONS')),
    published_at TIMESTAMPTZ NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL
);

-- The Divia network, replaced whole by each import: nothing refers to a stop.
CREATE TABLE transit_lines (
    name VARCHAR(20) PRIMARY KEY CHECK (length(btrim(name)) > 0),
    mode VARCHAR(4) NOT NULL CHECK (mode IN ('TRAM', 'BUS'))
);

-- The mode of a stop is the one the import computed from its lines: tram when a tram calls.
CREATE TABLE transit_stops (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (length(btrim(name)) > 0),
    mode VARCHAR(4) NOT NULL CHECK (mode IN ('TRAM', 'BUS')),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    location GEOGRAPHY(POINT, 4326)
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::GEOGRAPHY) STORED
);

CREATE INDEX transit_stops_location_idx ON transit_stops USING GIST (location);

CREATE TABLE transit_stop_lines (
    stop_id INTEGER NOT NULL REFERENCES transit_stops (id) ON DELETE CASCADE,
    line_name VARCHAR(20) NOT NULL REFERENCES transit_lines (name),
    PRIMARY KEY (stop_id, line_name)
);

-- The streets of the Base Adresse Nationale in the communes of the metropolis, replaced whole by
-- each import. The position of a street is its house number closest to its middle.
CREATE TABLE address_streets (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commune_code VARCHAR(5) NOT NULL REFERENCES reference.communes (code),
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    UNIQUE (commune_code, name)
);

CREATE INDEX address_streets_name_idx ON address_streets USING GIN (search_text(name) gin_trgm_ops);

-- The label is the number with its repetition index ("6 bis"), as shown.
CREATE TABLE address_numbers (
    street_id INTEGER NOT NULL REFERENCES address_streets (id) ON DELETE CASCADE,
    label VARCHAR(16) NOT NULL CHECK (label ~ '^[0-9]+( [a-z]+)?$'),
    number INTEGER NOT NULL CHECK (number BETWEEN 1 AND 99998),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    PRIMARY KEY (street_id, label),
    CONSTRAINT address_numbers_label_number_check
        CHECK (label = number::TEXT OR label LIKE number::TEXT || ' %')
);

-- The establishments that declared themselves training organisations (public list of the
-- Ministry of Labour), replaced whole by each import.
CREATE TABLE training_organizations (
    siret VARCHAR(14) PRIMARY KEY CHECK (siret ~ '^[0-9]{14}$')
);
