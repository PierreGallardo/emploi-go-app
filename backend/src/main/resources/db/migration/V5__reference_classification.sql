-- Our classification of the NAF into the sectors of the map: a sector per division, then exceptions
-- by class or sub-class. A sub-class left without sector is not imported.

UPDATE reference.naf_divisions d
SET sector_code = s.sector_code
FROM (
    VALUES
        ('AGRICULTURE', ARRAY['01', '02', '03']),
        ('INDUSTRY', ARRAY[
            '05', '06', '07', '08', '09', '10', '11', '12', '13', '14', '15', '16', '17', '20',
            '21', '22', '23', '24', '25', '26', '27', '28', '30', '31', '32', '33']),
        ('MEDIA_COMMUNICATION', ARRAY['18', '58', '59', '60', '73']),
        ('ENERGY_ENVIRONMENT', ARRAY['19', '35', '36', '37', '38', '39']),
        ('AUTOMOTIVE', ARRAY['29', '45']),
        ('CONSTRUCTION', ARRAY['41', '42', '43']),
        ('RETAIL', ARRAY['46', '47', '95']),
        ('TRANSPORT_LOGISTICS', ARRAY['49', '50', '51', '52', '53']),
        ('HOSPITALITY_TOURISM', ARRAY['55', '56', '79']),
        ('IT_DIGITAL', ARRAY['61', '62', '63']),
        ('FINANCE_REAL_ESTATE', ARRAY['64', '65', '66', '68']),
        ('BUSINESS_SERVICES', ARRAY['69', '70', '71', '72', '74', '77', '78', '80', '81', '82']),
        ('HEALTH', ARRAY['75', '86']),
        ('PUBLIC_SERVICE', ARRAY['84']),
        ('EDUCATION_TRAINING', ARRAY['85']),
        ('SOCIAL_PERSONAL_SERVICES', ARRAY['87', '88', '94']),
        ('CULTURE_SPORT_LEISURE', ARRAY['90', '91', '92', '93']),
        ('BEAUTY_WELLNESS', ARRAY['96'])
) AS s(sector_code, divisions)
WHERE d.code = ANY (s.divisions);

-- 97 and 98 (households as employers: private persons) and 99 (extraterritorial bodies) stay
-- without sector.
UPDATE reference.naf_activities a
SET sector_code = d.sector_code
FROM reference.naf_divisions d
WHERE d.nomenclature = a.nomenclature AND d.code = a.division_code;

-- No prefix contains another one: each sub-class matches one exception at most.
UPDATE reference.naf_activities a
SET sector_code = e.sector_code
FROM (
    VALUES
        ('NAF_REV2', '10.13B', 'FOOD'),
        ('NAF_REV2', '10.52Z', 'FOOD'),
        ('NAF_REV2', '10.71C', 'FOOD'),
        ('NAF_REV2', '10.71D', 'FOOD'),
        ('NAF_REV2', '10.82Z', 'FOOD'),
        ('NAF_REV2', '47.2', 'FOOD'),
        ('NAF_REV2', '47.3', 'AUTOMOTIVE'),
        ('NAF_REV2', '58.2', 'IT_DIGITAL'),
        ('NAF_REV2', '64.2', NULL),
        ('NAF_REV2', '64.3', NULL),
        ('NAF_REV2', '74.2', 'MEDIA_COMMUNICATION'),
        ('NAF_REV2', '77.1', 'AUTOMOTIVE'),
        ('NAF_REV2', '93.13', 'BEAUTY_WELLNESS'),
        ('NAF_REV2', '95.1', 'IT_DIGITAL'),
        ('NAF_REV2', '96.01', 'SOCIAL_PERSONAL_SERVICES'),
        ('NAF_REV2', '96.03', 'SOCIAL_PERSONAL_SERVICES'),
        ('NAF_REV2', '96.09', 'SOCIAL_PERSONAL_SERVICES'),
        ('NAF_2025', '10.13H', 'FOOD'),
        ('NAF_2025', '10.52Y', 'FOOD'),
        ('NAF_2025', '10.71H', 'FOOD'),
        ('NAF_2025', '10.71J', 'FOOD'),
        ('NAF_2025', '10.82Y', 'FOOD'),
        ('NAF_2025', '46.7', 'AUTOMOTIVE'),
        ('NAF_2025', '47.2', 'FOOD'),
        ('NAF_2025', '47.3', 'AUTOMOTIVE'),
        ('NAF_2025', '47.8', 'AUTOMOTIVE'),
        ('NAF_2025', '58.2', 'IT_DIGITAL'),
        ('NAF_2025', '64.2', NULL),
        ('NAF_2025', '64.3', NULL),
        ('NAF_2025', '74.2', 'MEDIA_COMMUNICATION'),
        ('NAF_2025', '77.1', 'AUTOMOTIVE'),
        ('NAF_2025', '93.13', 'BEAUTY_WELLNESS'),
        ('NAF_2025', '95.1', 'IT_DIGITAL'),
        ('NAF_2025', '95.3', 'AUTOMOTIVE'),
        ('NAF_2025', '96.1', 'SOCIAL_PERSONAL_SERVICES'),
        ('NAF_2025', '96.3', 'SOCIAL_PERSONAL_SERVICES'),
        ('NAF_2025', '96.4', 'SOCIAL_PERSONAL_SERVICES'),
        ('NAF_2025', '96.9', 'SOCIAL_PERSONAL_SERVICES')
) AS e(nomenclature, prefix, sector_code)
WHERE a.nomenclature = e.nomenclature AND a.code LIKE e.prefix || '%';

-- The jobs that France Travail links to a division whose part without exception is not where they
-- work: each refined division is replaced by the listed sub-classes, the other divisions of the job
-- keep the default rule.
INSERT INTO reference.job_division_refinements (rome_code, division, activity_code)
VALUES
    -- Food craft trades: the shop and the workshop, not the food industry or general retail.
    ('A1412', '10', '10.51C'),
    ('A1412', '47', '47.29Z'),
    ('D1101', '10', '10.11Z'),
    ('D1101', '10', '10.13B'),
    ('D1101', '47', '47.22Z'),
    ('D1102', '10', '10.71C'),
    ('D1102', '47', '47.24Z'),
    ('D1103', '10', '10.13B'),
    ('D1104', '10', '10.71C'),
    ('D1104', '10', '10.71D'),
    ('D1105', '10', '10.20Z'),
    ('D1105', '47', '47.23Z'),
    ('D1106', '47', '47.11B'),
    ('D1106', '47', '47.11C'),
    ('D1106', '47', '47.29Z'),
    ('D1108', '10', '10.71C'),
    ('D1109', '10', '10.11Z'),
    ('D1109', '10', '10.13B'),
    ('D1109', '47', '47.22Z'),
    ('D1111', '10', '10.13A'),
    ('D1111', '10', '10.13B'),
    ('D1112', '10', '10.13B'),
    ('D1112', '47', '47.22Z'),
    ('D1115', '10', '10.82Z'),
    ('D1117', '10', '10.71C'),
    ('D1117', '10', '10.71D'),
    ('D1117', '47', '47.24Z'),
    ('D1118', '10', '10.52Z'),
    ('D1120', '10', '10.71C'),
    ('D1120', '10', '10.71D'),
    ('G1608', '47', '47.23Z'),
    ('D1419', '47', '47.25Z'),
    ('G1804', '47', '47.25Z'),
    ('G1806', '47', '47.25Z'),
    ('G1807', '47', '47.26Z'),
    ('D1251', '47', '47.26Z'),
    ('D1251', '47', '47.62Z'),
    -- Service stations.
    ('D1303', '47', '47.30Z'),
    ('D1511', '47', '47.30Z'),
    -- Hair, beauty and dry cleaning, which France Travail also links to retail.
    ('D1202', '47', '47.75Z'),
    ('D1225', '47', '47.75Z'),
    ('D1205', '47', '96.01B'),
    ('D1229', '47', '96.01B'),
    ('D1229', '96', '96.01B'),
    ('D1226', '96', '96.01B'),
    ('D1228', '96', '96.01B'),
    ('D1236', '96', '96.01B'),
    ('D1242', '96', '96.01A'),
    ('D1242', '96', '96.01B'),
    ('H2511', '96', '96.01A'),
    ('D1304', '96', '45.20A'),
    -- Software and video games.
    ('E1115', '58', '58.21Z'),
    ('E1125', '58', '58.21Z'),
    ('M1844', '58', '58.29A'),
    ('M1844', '58', '58.29B'),
    ('M1844', '58', '58.29C'),
    -- Photography.
    ('E1201', '74', '74.20Z'),
    ('E1203', '74', '74.20Z'),
    ('E1208', '74', '74.20Z'),
    ('E1209', '74', '74.20Z'),
    -- Vehicle rental.
    ('I1109', '77', '77.11A'),
    ('I1109', '77', '77.11B'),
    ('I1109', '77', '77.12Z'),
    -- Fitness rooms.
    ('G1217', '93', '93.13Z'),
    -- Computer and communication equipment repair.
    ('I1401', '95', '95.11Z'),
    ('I1407', '95', '95.12Z'),
    ('I1409', '95', '95.11Z'),
    ('I1411', '95', '95.11Z'),
    ('I1411', '95', '95.12Z'),
    -- Funeral services.
    ('K2601', '96', '96.03Z'),
    ('K2602', '96', '96.03Z'),
    ('K2603', '96', '96.03Z'),
    ('K2604', '96', '96.03Z'),
    ('K2605', '96', '96.03Z'),
    ('K2606', '96', '96.03Z'),
    ('N4129', '96', '96.03Z'),
    -- Other personal services: animals, home help, coaching, investigation.
    ('A1426', '96', '96.09Z'),
    ('A1503', '96', '96.09Z'),
    ('A1509', '96', '96.09Z'),
    ('A1511', '96', '96.09Z'),
    ('K2326', '96', '96.09Z'),
    ('I1204', '96', '96.09Z'),
    ('K1103', '96', '96.09Z'),
    ('K1302', '96', '96.09Z'),
    ('K1304', '96', '96.09Z'),
    ('K1306', '96', '96.09Z'),
    ('K1311', '96', '96.09Z'),
    ('K1313', '96', '96.09Z'),
    ('K1408', '96', '96.09Z'),
    ('K2139', '96', '96.09Z'),
    ('K2505', '96', '96.09Z'),
    ('L1312', '96', '96.09Z'),
    ('N2207', '96', '96.09Z'),
    ('N3108', '96', '96.09Z'),
    ('N4108', '96', '96.09Z');

REFRESH MATERIALIZED VIEW reference.job_activities;
ANALYZE reference.naf_divisions, reference.naf_activities, reference.job_division_refinements,
    reference.job_activities;
