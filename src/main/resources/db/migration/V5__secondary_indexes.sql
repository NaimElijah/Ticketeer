-- Secondary indexes (#186).
--
-- V1 created only primary keys and unique constraints. PostgreSQL does not index a foreign key's column
-- on its own (H2 does, which hid this in dev and the H2 suite), so every eager load of a parent's
-- collection, and every repository query on a non-key column, scanned the whole table.
-- SchemaMigrationIT checks on PostgreSQL that every foreign-key column, and every column a repository
-- filters on, leads an index.
--
-- A plain CREATE INDEX blocks writes to its table while it builds. That is brief at the current table
-- sizes. CREATE INDEX CONCURRENTLY would avoid it, but H2 does not accept it and PostgreSQL refuses it
-- inside the transaction Flyway runs each migration in.

-- Foreign-key columns: the collection loads.
create index ix_company_owner_ids_company_id on company_owner_ids (company_id);
create index ix_company_managers_company_id on company_managers (company_id);
create index ix_appointment_permissions_appointment_id on appointment_permissions (appointment_id);
create index ix_inventory_zones_venue_map_id on inventory_zones (venue_map_id);
create index ix_seats_zone_pk on seats (zone_pk);
create index ix_event_artists_event_id on event_artists (event_id);
create index ix_event_show_dates_event_id on event_show_dates (event_id);
create index ix_active_order_items_order_key on active_order_items (order_key);
create index ix_receipt_lines_receipt_id on receipt_lines (receipt_id);
create index ix_receipt_transactions_receipt_id on receipt_transactions (receipt_id);
create index ix_messages_conversation_id on messages (conversation_id);

-- Columns the repositories filter on.
create index ix_events_company_id on events (company_id);
create index ix_events_status on events (status);
create index ix_sessions_expires_at on sessions (expires_at);
create index ix_companies_name on companies (name);
create index ix_company_appointments_company_id on company_appointments (company_id);
create index ix_company_appointments_target_id on company_appointments (target_id);
create index ix_conversations_initiator_id on conversations (initiator_id);
create index ix_conversations_counterparty_id on conversations (counterparty_id);
-- The notification poll reads one recipient's notifications in one status, every minute per UI.
create index ix_notifications_recipient_user_id_status on notifications (recipient_user_id, status);
create index ix_active_orders_session_id on active_orders (session_id);
create index ix_active_order_items_added_at on active_order_items (added_at);
create index ix_receipts_user_id on receipts (user_id);
create index ix_receipt_lines_event_id on receipt_lines (event_id);
create index ix_tickets_event_id on tickets (event_id);
create index ix_tickets_order_receipt_id on tickets (order_receipt_id);
