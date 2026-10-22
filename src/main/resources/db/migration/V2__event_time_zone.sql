-- Event time zones (#74, ADR-008).
--
-- An event's show dates (event_show_dates) are wall-clock times at the venue; time_zone is the IANA
-- zone they are read in. Every producer so far worked in Israel, so existing rows are backfilled
-- with Asia/Jerusalem. Changing platform.time-zone later does not rewrite them.
--
-- The default stays while an instance built before #74 may still insert events without the column
-- (expand-only, ADR-007); a later migration drops it. There is no check constraint: SQL cannot
-- validate an IANA id, and a bad value fails when the event is loaded.

alter table events add column time_zone varchar(64) default 'Asia/Jerusalem' not null;
