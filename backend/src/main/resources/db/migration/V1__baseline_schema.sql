-- V1 — Baseline schema.
--
-- This is the schema exactly as `spring.jpa.hibernate.ddl-auto=update` had built it
-- up to 2026-09-21, generated from the entity mappings themselves (Hibernate 7.4 /
-- MySQLDialect) rather than transcribed by hand, so it matches what is already live
-- on Aiven and in local dev databases column for column.
--
-- Existing databases are NOT re-created: `spring.flyway.baseline-on-migrate=true`
-- stamps them at V1 and starts applying from V2. Only a fresh database runs this file.
--
-- From here on, every schema change is a new V<n>__ file. `ddl-auto` is `validate`,
-- so Hibernate will refuse to start if the entities and the migrations disagree.


create table activity (
    calories_burned integer,
    difficulty_rating integer,
    duration integer,
    completed_at datetime(6),
    created_at datetime(6) not null,
    start_time datetime(6),
    updated_at datetime(6) not null,
    notes varchar(1000),
    id varchar(255) not null,
    user_id varchar(255) not null,
    additional_metrics json,
    type enum ('CARDIO','CYCLING','HIIT','OTHER','RUNNING','STRETCHING','SWIMMING','WALKING','WEIGHT_TRAINING','YOGA'),
    primary key (id)
) engine=InnoDB;

create table body_metrics_log (
    bmi float(53),
    height float(53),
    weight float(53),
    logged_at datetime(6) not null,
    bmi_category varchar(255),
    id varchar(255) not null,
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table challenge (
    creator_score float(53),
    end_date date,
    opponent_score float(53),
    start_date date,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    creator_id varchar(255) not null,
    id varchar(255) not null,
    opponent_id varchar(255) not null,
    result_message varchar(255),
    title varchar(255),
    winner_id varchar(255),
    status enum ('ACTIVE','CANCELLED','COMPLETED','DECLINED','PENDING'),
    type enum ('CALORIES_BURNED','STREAK_LENGTH','TOTAL_DURATION','WORKOUT_COUNT'),
    primary key (id)
) engine=InnoDB;

create table challenge_leaderboard_entry (
    creator_score float(53),
    lead_margin float(53),
    opponent_score float(53),
    snapshot_date date,
    created_at datetime(6) not null,
    challenge_id varchar(255) not null,
    id varchar(255) not null,
    leading_user_id varchar(255),
    primary key (id)
) engine=InnoDB;

create table fitness_user (
    age integer,
    current_streak integer,
    height float(53),
    last_activity_date date,
    longest_streak integer,
    weight float(53),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    email varchar(255),
    first_name varchar(255),
    id varchar(255) not null,
    last_name varchar(255),
    password varchar(255),
    role enum ('ADMIN','USER'),
    primary key (id)
) engine=InnoDB;

create table goal (
    current_value float(53),
    deadline date,
    is_completed bit,
    period_start date,
    target_value float(53),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    id varchar(255) not null,
    user_id varchar(255) not null,
    type enum ('CALORIES_PER_WEEK','DURATION_PER_WEEK','WORKOUTS_PER_WEEK'),
    primary key (id)
) engine=InnoDB;

create table muscle_recovery (
    recommended_rest_hours integer not null,
    last_worked_at datetime(6),
    updated_at datetime(6) not null,
    id varchar(255) not null,
    user_id varchar(255) not null,
    muscle_group enum ('ARMS','BACK','CALVES','CHEST','CORE','FULL_BODY','GLUTES','HAMSTRINGS','LEGS','QUADRICEPS','SHOULDERS'),
    primary key (id)
) engine=InnoDB;

create table pain_log (
    severity integer,
    logged_at datetime(6) not null,
    activity_id varchar(255),
    description varchar(255),
    id varchar(255) not null,
    user_id varchar(255) not null,
    body_part enum ('HIPS','LEFT_ANKLE','LEFT_KNEE','LEFT_SHOULDER','LOWER_BACK','NECK','OTHER','RIGHT_ANKLE','RIGHT_KNEE','RIGHT_SHOULDER','UPPER_BACK'),
    primary key (id)
) engine=InnoDB;

create table personal_best (
    best_calories integer,
    best_duration integer,
    achieved_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    id varchar(255) not null,
    user_id varchar(255) not null,
    activity_type enum ('CARDIO','CYCLING','HIIT','OTHER','RUNNING','STRETCHING','SWIMMING','WALKING','WEIGHT_TRAINING','YOGA'),
    primary key (id)
) engine=InnoDB;

create table recommendation (
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    recommendation varchar(2000),
    activity_id varchar(255),
    id varchar(255) not null,
    type varchar(255),
    user_id varchar(255) not null,
    improvements json,
    safety json,
    suggestions json,
    primary key (id)
) engine=InnoDB;

create table refresh_token (
    revoked bit,
    created_at datetime(6) not null,
    expires_at datetime(6),
    id varchar(255) not null,
    token varchar(255) not null,
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table sleep_log (
    hours_slept float(53),
    quality_rating integer,
    sleep_date date,
    bedtime datetime(6),
    logged_at datetime(6) not null,
    wake_time datetime(6),
    id varchar(255) not null,
    notes varchar(255),
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table user_plan_enrollment (
    completed_at date,
    completed_days integer,
    current_day integer,
    is_active bit,
    last_completed_date date,
    start_date date,
    enrolled_at datetime(6) not null,
    id varchar(255) not null,
    plan_id varchar(255) not null,
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table water_log (
    amount_ml float(53),
    log_date date,
    logged_at datetime(6) not null,
    id varchar(255) not null,
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table workout_plan (
    total_days integer,
    created_at datetime(6) not null,
    created_by_user_id varchar(255),
    description varchar(255),
    difficulty varchar(255),
    goal varchar(255),
    id varchar(255) not null,
    name varchar(255),
    primary key (id)
) engine=InnoDB;

create table workout_plan_day (
    day_number integer,
    duration_minutes integer,
    is_rest_day bit,
    id varchar(255) not null,
    instructions varchar(255),
    plan_id varchar(255) not null,
    activity_type enum ('CARDIO','CYCLING','HIIT','OTHER','RUNNING','STRETCHING','SWIMMING','WALKING','WEIGHT_TRAINING','YOGA'),
    primary key (id)
) engine=InnoDB;

alter table fitness_user 
   add constraint UKka640gbkjnj296fik7wmf8dss unique (email);

alter table refresh_token 
   add constraint UKr4k4edos30bx9neoq81mdvwph unique (token);

alter table activity 
   add constraint fk_activity_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table body_metrics_log 
   add constraint fk_body_metrics_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table challenge 
   add constraint fk_challenge_creator 
   foreign key (creator_id) 
   references fitness_user (id);

alter table challenge 
   add constraint fk_challenge_opponent 
   foreign key (opponent_id) 
   references fitness_user (id);

alter table challenge 
   add constraint fk_challenge_winner 
   foreign key (winner_id) 
   references fitness_user (id);

alter table challenge_leaderboard_entry 
   add constraint fk_leaderboard_challenge 
   foreign key (challenge_id) 
   references challenge (id);

alter table goal 
   add constraint fk_goal_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table muscle_recovery 
   add constraint fk_muscle_recovery_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table pain_log 
   add constraint fk_pain_log_activity 
   foreign key (activity_id) 
   references activity (id);

alter table pain_log 
   add constraint fk_pain_log_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table personal_best 
   add constraint fk_personal_best_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table recommendation 
   add constraint fk_recommendation_activity 
   foreign key (activity_id) 
   references activity (id);

alter table recommendation 
   add constraint fk_recommendation_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table refresh_token 
   add constraint fk_refresh_token_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table sleep_log 
   add constraint fk_sleep_log_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table user_plan_enrollment 
   add constraint fk_enrollment_plan 
   foreign key (plan_id) 
   references workout_plan (id);

alter table user_plan_enrollment 
   add constraint fk_enrollment_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table water_log 
   add constraint fk_water_log_user 
   foreign key (user_id) 
   references fitness_user (id);

alter table workout_plan_day 
   add constraint fk_plan_day_plan 
   foreign key (plan_id) 
   references workout_plan (id);
