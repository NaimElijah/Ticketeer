-- Index for sessions.user_id (#187).
--
-- SessionRepository.deleteAdminSessions (SpringDataSessionRepository.deleteByUserIdAndKind) is the first
-- production query on sessions.user_id: CommittedAdminPasswordRotation runs it at boot to end an admin's
-- sessions (ADR-021). Without an index PostgreSQL scans the whole sessions table for it. SchemaMigrationIT
-- lists the column as queried.
--
-- Expand-only. A plain CREATE INDEX blocks writes to sessions while it builds, which is brief at the
-- current table size (see V5 for why it is not CONCURRENTLY).

create index ix_sessions_user_id on sessions (user_id);
