-- receipt_lines.refund_state is never null (#175, ADR-001).
--
-- The column was nullable only because ddl-auto "update" could not add a NOT NULL column to a populated
-- table; ADR-007 replaced update with these migrations. A null meant NONE, so every reader had to know
-- the rule. The backfill writes that NONE into the row, then the column refuses a null.
--
-- A receipt refunded before refund state moved onto the lines records the refund only in
-- receipts.is_refunded, with every line NONE. The backfill keeps that shape, and OrderReceipt still
-- counts such a receipt's lines as refunded, so no receipt changes meaning. Every build since the line
-- state existed writes NONE, so the backfill touches only rows older than that.

update receipt_lines set refund_state = 'NONE' where refund_state is null;
alter table receipt_lines alter column refund_state set not null;
