-- V9 — A per-account token version, so access tokens can be retired (PLAN P4-3).
--
-- Access tokens are signed JWTs that nothing checked against the account, so one issued
-- before a password change or reset kept working until it expired, up to 48 hours later,
-- even though every refresh token had been revoked. Each access token now carries the
-- version the account had when it was issued, and the request is refused once the
-- account's version has moved on. A password change or reset adds one.
--
-- Every account starts at 0, and a token with no version claim (all of those issued
-- before this) counts as 0, so nobody is signed out by the upgrade itself.
--
-- Portable SQL: the same statement runs on MySQL and on the H2 the tests use.

alter table fitness_user add column token_version int not null default 0;
