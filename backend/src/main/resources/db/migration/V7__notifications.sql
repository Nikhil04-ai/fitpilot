-- V7 — The delivery layer (PLAN P2-2).
--
-- Three scheduled jobs produced insight the user was never told about: a plateau scan
-- every Monday at 09:00, challenge scoring at 23:55 and settlement at 00:05. Each was
-- visible only if the user happened to open the right panel on the right day — and the
-- plateau scan produced nothing at all, being a log line and a TODO. Work nobody is
-- told about may as well not have run.

create table notification (
    created_at datetime(6) not null,
    read_at datetime(6),
    id varchar(255) not null,
    user_id varchar(255) not null,
    target_panel varchar(255),
    title varchar(255),
    dedupe_key varchar(255),
    body varchar(1000),
    type enum ('CHALLENGE_RESULT','GENERAL','GOAL_DEADLINE','PERSONAL_BEST','PLATEAU','WEEKLY_DIGEST'),
    primary key (id)
) engine=InnoDB;

alter table notification
    add constraint fk_notification_user
    foreign key (user_id)
    references fitness_user (id);

-- Every read is "this user, newest first", and the unread badge counts on the same
-- pair. The foreign key indexes user_id alone, which would still sort a user's whole
-- history to find the newest page.
create index idx_notification_user_created on notification (user_id, created_at);

-- What stops a daily job re-announcing the same goal deadline every morning: the
-- producer checks this pair before inserting. Not unique, because a null dedupe_key
-- means "announce this every time" and several rows legitimately carry one.
create index idx_notification_dedupe on notification (user_id, dedupe_key);
