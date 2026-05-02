-- V5__fix_coordinate_column_types.sql
-- Changes latitude/longitude from DECIMAL to DOUBLE PRECISION to match
-- the Java Double type mapping expected by Hibernate schema validation.
-- DECIMAL(10,8) was semantically correct but Hibernate maps Java Double
-- to SQL FLOAT8 (double precision), causing ddl-auto=validate to fail.

ALTER TABLE parking_lots
    ALTER COLUMN latitude  TYPE DOUBLE PRECISION USING latitude::DOUBLE PRECISION,
    ALTER COLUMN longitude TYPE DOUBLE PRECISION USING longitude::DOUBLE PRECISION;
