-- V6 — The weekly digest (PLAN P2-3).
--
-- Gemini was called from exactly two places, and the coaching call was fed age, weight,
-- the last five activities and a streak number. Sleep, pain, recovery, timing, body
-- weight, per-movement load and everything eaten were all computed by rule-based
-- services and never reached the model. This table holds the output of a call that sees
-- all of it at once.
--
-- data_points is the load-bearing column: the figures the narrative was written from,
-- stored alongside it. The model is handed the facts and asked to interpret them, never
-- to supply them, and keeping them here is what makes a digest checkable months later
-- instead of something the reader has to take on faith.

create table weekly_digest (
    ai_generated bit,
    week_start date,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    headline varchar(255),
    id varchar(255) not null,
    user_id varchar(255) not null,
    narrative varchar(2000),
    data_points json,
    focus_next_week json,
    observations json,
    primary key (id)
) engine=InnoDB;

-- One digest per user per week. Regenerating replaces rather than accumulating a pile
-- of near-identical summaries of the same seven days, and the constraint is what makes
-- the upsert in WeeklyDigestService safe against two concurrent generate calls.
alter table weekly_digest
    add constraint uk_weekly_digest_user_week unique (user_id, week_start);

alter table weekly_digest
    add constraint fk_weekly_digest_user
    foreign key (user_id)
    references fitness_user (id);
