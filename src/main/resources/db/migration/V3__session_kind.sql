-- Session kind (#185, ADR-012).
--
-- Member ids and admin ids share one integer space, so a session row's user_id cannot say whether
-- it names a member or an admin; kind does. GUEST rows have no user_id, MEMBER and ADMIN rows have
-- one, and ck_sessions_kind_user_id holds that rule.
--
-- Rows written before this migration did not record their kind, and a row with a user_id may be a
-- member's or an admin's. Guessing MEMBER for all of them would turn a live admin session into a
-- session of the member who has the admin's id. So every row with a user_id is deleted, and
-- everyone signed in when this runs, members and admins alike, signs in again. Sessions are
-- transient (at most one member TTL), so no other data is lost. Guest rows are kept as GUEST rows.
--
-- This is a recorded exception to ADR-007's expand-only rule (ADR-012): kind is NOT NULL with no
-- default, because no single kind fits both the guest rows and the member rows a pre-#185 instance
-- inserts, so that instance's session inserts fail until it stops.

delete from sessions where user_id is not null;

alter table sessions add column kind varchar(255);

update sessions set kind = 'GUEST';

alter table sessions alter column kind set not null;

alter table sessions add constraint ck_sessions_kind check (kind in ('GUEST','MEMBER','ADMIN'));
alter table sessions add constraint ck_sessions_kind_user_id
    check ((kind = 'GUEST' and user_id is null) or (kind <> 'GUEST' and user_id is not null));
