-- One active order per member (#186, business rule §1 #8).
--
-- On the Jpa adapters the order lock is a no-op, so two first reservations by one member at once each
-- found no cart and created their own. Both could commit, or the later save deleted the earlier cart and
-- left its seats reserved under an order key no cart holds, which nothing ever releases. The database
-- now holds the rule, and JpaActiveOrderRepository turns this constraint's violation into
-- ConcurrentReservationException, which rolls back the losing reservation with its seats.
--
-- Guest carts have no user, and NULLs are distinct in a unique constraint on both PostgreSQL and H2, so
-- this does not touch them.
--
-- Two carts of one member, written by that race before this migration, fail this statement, and Flyway
-- stops the boot. Release the older cart's lines and delete it, then boot again.

alter table active_orders add constraint uk_active_orders_user_id unique (user_id);
