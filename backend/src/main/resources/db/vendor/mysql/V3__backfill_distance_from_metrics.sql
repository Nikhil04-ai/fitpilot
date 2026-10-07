-- V3 — Backfill activity.distance_km from the additional_metrics JSON (MySQL only).
--
-- Every distance logged before V2 lives inside the additional_metrics blob, put there
-- by the Gemini parser as "distance_km". V2 gave it a column; without this, all of
-- that history stays invisible to stats and records and only new workouts count.
--
-- MySQL-only, and deliberately in a separate location from db/migration: it needs
-- JSON_EXTRACT, which H2 does not implement, and Flyway scans a location
-- recursively — nesting this under db/migration would feed it to the H2-backed
-- FlywayMigrationTest as well. Data-only, so nothing about the schema goes untested
-- as a result.

update activity
set distance_km = cast(json_unquote(json_extract(additional_metrics, '$.distance_km')) as decimal(12, 4))
where distance_km is null
  and additional_metrics is not null
  and json_valid(additional_metrics)
  and json_extract(additional_metrics, '$.distance_km') is not null
  -- A parser that returned "about 5km" stored a string, not a number. Skip it rather
  -- than let MySQL coerce it to 0 and invent a distance nobody ran.
  and json_type(json_extract(additional_metrics, '$.distance_km')) in ('INTEGER', 'DOUBLE', 'DECIMAL');
