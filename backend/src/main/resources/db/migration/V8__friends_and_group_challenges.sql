-- V8 — Friends, and challenges that hold more than two people (PLAN P2-4).
--
-- Two problems with one root. Challenges shipped before any concept of a friend, so the
-- only way to challenge somebody was to know their raw UUID and paste it in — the
-- Profile panel has a "Your Athlete ID, share to receive challenges" box for exactly
-- that reason. And a challenge was 1-v-1 in the table, not merely in the UI: a
-- creator_id and an opponent_id, a creator_score and an opponent_score. Three people
-- were not an unimplemented feature, they were unrepresentable.

-- ── Friends ────────────────────────────────────────────────────────────────────

create table friendship (
    created_at datetime(6) not null,
    responded_at datetime(6),
    addressee_id varchar(255) not null,
    id varchar(255) not null,
    requester_id varchar(255) not null,
    status enum ('ACCEPTED','PENDING'),
    primary key (id)
) engine=InnoDB;

-- One row per pair, not two. A friendship is symmetric, so storing it from both sides
-- would be two rows that must agree about a single fact -- and the day they disagree,
-- one person has a friend who does not have them.
alter table friendship
    add constraint uk_friendship_pair unique (requester_id, addressee_id);

alter table friendship
    add constraint fk_friendship_requester
    foreign key (requester_id) references fitness_user (id);

alter table friendship
    add constraint fk_friendship_addressee
    foreign key (addressee_id) references fitness_user (id);

create index idx_friendship_requester on friendship (requester_id, status);
create index idx_friendship_addressee on friendship (addressee_id, status);

-- ── Challenge participants ─────────────────────────────────────────────────────

create table challenge_participant (
    is_creator bit,
    score float(53),
    invited_at datetime(6) not null,
    responded_at datetime(6),
    challenge_id varchar(255) not null,
    id varchar(255) not null,
    user_id varchar(255) not null,
    status enum ('ACCEPTED','DECLINED','INVITED'),
    primary key (id)
) engine=InnoDB;

alter table challenge_participant
    add constraint uk_challenge_participant unique (challenge_id, user_id);

alter table challenge_participant
    add constraint fk_challenge_participant_challenge
    foreign key (challenge_id) references challenge (id);

alter table challenge_participant
    add constraint fk_challenge_participant_user
    foreign key (user_id) references fitness_user (id);

create index idx_challenge_participant_user on challenge_participant (user_id);

-- Move every existing challenge into the new shape before the old columns go.
--
-- Two inserts rather than one with a UNION, so each side can carry its own is_creator
-- flag and status without a CASE over a joined set. Ordinary INSERT..SELECT, so it runs
-- on H2 in the migration test exactly as it does on MySQL.
--
-- The creator is ACCEPTED by definition: they proposed it.
insert into challenge_participant
    (id, challenge_id, user_id, status, score, is_creator, invited_at, responded_at)
select
    concat(c.id, '-c'), c.id, c.creator_id, 'ACCEPTED', coalesce(c.creator_score, 0), true,
    c.created_at, c.created_at
from challenge c;

-- The opponent's status follows the challenge they were invited to: a PENDING challenge
-- means they never answered, DECLINED means they said no, and anything else means they
-- were in it.
insert into challenge_participant
    (id, challenge_id, user_id, status, score, is_creator, invited_at, responded_at)
select
    concat(c.id, '-o'), c.id, c.opponent_id,
    case c.status
        when 'PENDING'  then 'INVITED'
        when 'DECLINED' then 'DECLINED'
        else 'ACCEPTED'
    end,
    coalesce(c.opponent_score, 0), false,
    c.created_at,
    case when c.status = 'PENDING' then null else c.updated_at end
from challenge c;

-- Now the old shape can go. Dropping rather than leaving them is the point: two places
-- holding the same score is two places that can disagree, and the parent row's copy
-- would have been the stale one every time.
alter table challenge drop constraint fk_challenge_opponent;
alter table challenge drop column opponent_id;
alter table challenge drop column creator_score;
alter table challenge drop column opponent_score;

-- ── Leaderboard snapshots ──────────────────────────────────────────────────────

-- "creator_score and opponent_score" cannot describe three people, and a third column
-- would only have moved the same wall out by one. A snapshot is read and written whole
-- and never queried by participant, so JSON is the right shape for it.
alter table challenge_leaderboard_entry add column standings json;

alter table challenge_leaderboard_entry drop column creator_score;
alter table challenge_leaderboard_entry drop column opponent_score;
