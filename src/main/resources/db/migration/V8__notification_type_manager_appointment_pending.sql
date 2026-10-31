-- Manager appointment pending (#187).
--
-- A manager invitation now raises a MANAGER_APPOINTMENT_PENDING notification instead of telling the
-- invitee their role has already changed. ddl-auto: validate does not check enum check constraints, so
-- without this the insert of the new type fails ck_notifications_type at runtime.
--
-- Expand-only: the constraint accepts every value it accepted before, plus the new one. Neither H2 nor
-- PostgreSQL can alter a check constraint in place, so it is dropped and added again under its name.
-- Rolling back below this release first needs
-- update notifications set type = 'ROLE_CHANGED' where type = 'MANAGER_APPOINTMENT_PENDING'
-- (or deleting those rows), because an older build cannot read the new value.

alter table notifications drop constraint ck_notifications_type;

alter table notifications add constraint ck_notifications_type check (type in ('PURCHASE_CONFIRMED','REFUND_ISSUED','EVENT_CANCELLED','CART_EXPIRING','EVENT_SOLD_OUT','COMPANY_CLOSED','ROLE_CHANGED','OWNER_APPOINTMENT_PENDING','MANAGER_APPOINTMENT_PENDING','MANAGER_REVOKED','DIRECT_MESSAGE','PURCHASE_FAILED','TICKET_RESERVATION_SUCCESS','TICKET_RESERVATION_FAILURE','REMOVE_TICKET_RESERVATION_FAILURE','REMOVE_TICKET_RESERVATION_SUCCESS'));
