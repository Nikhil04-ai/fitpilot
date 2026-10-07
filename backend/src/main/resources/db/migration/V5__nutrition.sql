-- V5 — Nutrition and calorie intake (PLAN P2-1).
--
-- The app measured one side of the energy equation. CalorieCalculatorService worked out
-- what a workout burned; nothing recorded what went in. That is the reason goal_type was
-- three activity-side weekly counters and nothing about body weight — with no intake
-- there is no deficit to compute, so a weight goal could not be scored at all.
--
-- Three changes, and the last one is the one to be careful with.

create table meal_log (
    calories integer,
    carbs_grams float(53),
    fat_grams float(53),
    protein_grams float(53),
    ai_estimated bit,
    logged_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    id varchar(255) not null,
    user_id varchar(255) not null,
    description varchar(500),
    meal_type enum ('BREAKFAST','DINNER','LUNCH','OTHER','SNACK'),
    primary key (id)
) engine=InnoDB;

alter table meal_log
    add constraint fk_meal_log_user
    foreign key (user_id)
    references fitness_user (id);

-- Every read of the meal log is "this user, this day" or "this user, this month":
-- the daily summary, the net-calorie series and the goal SUMs all filter on exactly
-- this pair. The foreign key indexes user_id alone, which would still scan a user's
-- whole history to find one day.
create index idx_meal_log_user_logged_at on meal_log (user_id, logged_at);

-- Where a body-weight goal started. "72kg" alone says nothing about whether the user
-- is cutting or bulking, and a progress bar needs a zero point — 80 -> 72 is half done
-- at 76kg, which cannot be derived from the target and today's weight. Null on every
-- accumulating goal, where progress starts from 0 by definition.
alter table goal
    add column start_value float(53);

-- goal.type is a MySQL ENUM, so the three new members have to be added to the column
-- definition or every insert of one fails with a truncation error. This is exactly the
-- kind of change ddl-auto=update would never have made (it only ever adds, never
-- redefines), and it would have failed on Aiven long after passing locally.
--
-- Listed alphabetically to match what Hibernate generates, so schema validation sees
-- the same string it would have written itself.
alter table goal
    modify column type enum (
        'CALORIES_PER_WEEK',
        'CALORIE_INTAKE_PER_DAY',
        'DURATION_PER_WEEK',
        'PROTEIN_PER_DAY',
        'TARGET_WEIGHT',
        'WORKOUTS_PER_WEEK'
    );
