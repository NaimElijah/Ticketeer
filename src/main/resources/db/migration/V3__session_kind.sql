-- Session kind (#185).
--
-- Member ids and admin ids share one integer space, so a session row's user_id cannot say whether
-- it names a member or an admin; kind does. GUEST rows have no user_id, MEMBER and ADMIN rows have
-- one, and ck_sessions_kind_user_id holds that rule.
--
-- Rows written before this migration did not record their kind. The backfill derives it from
-- user_id alone, so a live admin session becomes a MEMBER row, which no admin token is accepted
-- against: admins signed in across the upgrade sign in again. Sessions are transient (at most one
-- member TTL), so no other data is lost.
--
-- Unlike V2 there is no default to keep a pre-#185 instance writing: no single kind fits both the
-- guest rows and the member rows it inserts, so its session inserts fail until it stops (ADR-007).

alter table sessions add column kind varchar(255);

update sessions set kind = 'GUEST' where user_id is null;
update sessions set kind = 'MEMBER' where user_id is not null;

alter table sessions alter column kind set not null;

alter table sessions add constraint ck_sessions_kind check (kind in ('GUEST','MEMBER','ADMIN'));
alter table sessions add constraint ck_sessions_kind_user_id
    check ((kind = 'GUEST' and user_id is null) or (kind <> 'GUEST' and user_id is not null));
