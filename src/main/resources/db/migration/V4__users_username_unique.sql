-- One username, one user (#185).
--
-- register checks existsByUsername and then inserts, so two registrations of one name can both pass
-- the check before either commits. Business rule §1 #1 makes usernames unique; the database now
-- holds that rule, and JpaUserRepository turns this constraint's violation into
-- DuplicateUsernameException.
--
-- No existing data needs a fix first. SystemIntegrityVerifier refuses to initialise the platform on
-- a duplicate username, so no database a platform has started on holds one. A duplicate written by
-- that race since the last start would fail this statement, and Flyway stops the boot: the same
-- outcome the verifier gives at the next initialisation. Remove the duplicate row, then boot again.

alter table users add constraint uk_users_username unique (username);
