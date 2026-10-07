-- V4 - Password reset grants (PLAN P1-4).
--
-- Numbered 4, not 3: V3 is the MySQL-only distance backfill. Vendor migrations live in
-- a separate location but share one version namespace and one schema-history table, so
-- reusing 3 here would be a duplicate-version failure on MySQL. A gap is the correct
-- outcome on H2, where V3 does not apply.
--
-- Only a hash of each token is stored - see PasswordResetToken for why.

create table password_reset_token (
    created_at datetime(6) not null,
    expires_at datetime(6),
    used_at datetime(6),
    id varchar(255) not null,
    token_hash varchar(255) not null,
    user_id varchar(255) not null,
    primary key (id)
) engine=InnoDB;

-- Unique because a reset is looked up by hash, and because two grants can never
-- legitimately share one.
alter table password_reset_token
    add constraint uk_password_reset_token_hash unique (token_hash);

alter table password_reset_token
    add constraint fk_password_reset_user
    foreign key (user_id)
    references fitness_user (id);
