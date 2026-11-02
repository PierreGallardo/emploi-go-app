-- The rights of the API and of the worker on the map, given by the migration identity after the
-- migrations of a delivery, before the API and the worker start:
--   psql -v ON_ERROR_STOP=1 -v api=<role of the API> -v worker=<role of the worker> -f map.sql
-- Each statement can run again. The columns the Mission Locale fills stay closed to the worker.

-- The reference data: read only, changed by the migrations alone.
GRANT USAGE ON SCHEMA reference TO :"api", :"worker";
GRANT SELECT ON ALL TABLES IN SCHEMA reference TO :"api", :"worker";

-- The API reads the map and writes none of it.
GRANT SELECT ON establishments, transit_stops, transit_lines, transit_stop_lines, address_streets,
    address_numbers, dataset_publications TO :"api";

-- The worker writes the State data of the establishments, never visible, description, image_file
-- nor jobs_url. An upsert reads the columns it sets.
GRANT SELECT (siret, name, company_name, address, commune_code, longitude, latitude,
        activity_nomenclature, activity_code, company_type, headcount_range,
        company_category, company_created_on, ess, seen_at, exclusion_reason, excluded_at),
    INSERT (siret, name, company_name, address, commune_code, longitude, latitude,
        activity_nomenclature, activity_code, company_type, headcount_range,
        company_category, company_created_on, ess, seen_at),
    UPDATE (name, company_name, address, commune_code, longitude, latitude,
        activity_nomenclature, activity_code, company_type, headcount_range,
        company_category, company_created_on, ess, seen_at, exclusion_reason, excluded_at),
    DELETE
    ON establishments TO :"worker";

-- The worker replaces the Divia stops and the addresses whole, and dates their publication. The
-- database deletes the lines of a stop and the numbers of a street with them, as their owner.
GRANT SELECT, INSERT, DELETE ON transit_stops, transit_lines, address_streets TO :"worker";
GRANT SELECT, INSERT ON transit_stop_lines, address_numbers TO :"worker";
GRANT USAGE ON SEQUENCE transit_stops_id_seq, address_streets_id_seq TO :"worker";

-- The worker replaces the list of the training organisations whole, and reads it for the import of
-- the establishments.
GRANT SELECT, INSERT, DELETE ON training_organizations TO :"worker";
GRANT SELECT, INSERT, UPDATE ON dataset_publications TO :"worker";
