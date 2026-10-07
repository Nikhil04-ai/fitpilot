-- V2 — Exercise-level logging (PLAN P1-1).
--
-- Adds the structure that makes strength training representable at all. Until now a
-- workout was duration + calories, which describes a run perfectly and a lifting
-- session not at all, so the only records the app could hold for WEIGHT_TRAINING were
-- "longest session" and "most calories" (PLAN P0-6).
--
-- Also promotes distance out of the additional_metrics JSON blob into a column. It was
-- being parsed and stored there all along, where nothing could sum, compare or index
-- it — so "ran 5km" was recorded and then invisible to stats and to records
-- (PLAN P0-2). Backfilling the existing blobs needs MySQL's JSON functions and lives
-- in the vendor-specific V3; this file stays portable so the test suite can run it
-- against H2 and validate the entity mapping against it.

alter table activity
    add column distance_km float(53);

alter table personal_best
    add column best_distance_km float(53);

create table exercise (
    order_index integer,
    reps integer,
    sets integer,
    weight_kg float(53),
    created_at datetime(6) not null,
    activity_id varchar(255) not null,
    id varchar(255) not null,
    name varchar(255),
    normalised_name varchar(255),
    primary key (id)
) engine=InnoDB;

create table exercise_personal_best (
    best_estimated_one_rep_max float(53),
    best_volume float(53),
    best_weight_kg float(53),
    best_weight_reps integer,
    best_bodyweight_reps integer,
    achieved_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    display_name varchar(255),
    id varchar(255) not null,
    normalised_name varchar(255),
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

-- One record row per movement per user. The unique constraint is what makes the
-- upsert in ExercisePersonalBestService safe against two concurrent saves of the
-- same lift creating two competing record rows.
alter table exercise_personal_best
    add constraint uk_exercise_pb_user_exercise unique (user_id, normalised_name);

alter table exercise
    add constraint fk_exercise_activity
    foreign key (activity_id)
    references activity (id);

alter table exercise_personal_best
    add constraint fk_exercise_pb_user
    foreign key (user_id)
    references fitness_user (id);

-- Records and progression both look a movement up by its normalised name across every
-- workout a user has. MySQL indexes activity_id for us as part of the foreign key;
-- this is the other half of that lookup.
create index idx_exercise_normalised_name on exercise (normalised_name);
