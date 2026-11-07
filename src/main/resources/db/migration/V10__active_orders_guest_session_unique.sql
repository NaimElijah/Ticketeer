-- One active order per guest session (#224, business rule §1 #8).
--
-- On the Jpa adapters the order lock is a no-op, so two first reservations from one guest session at once
-- (two tabs of one browser) each found no cart and created their own, and both committed with the same
-- session_id. No seat was stranded, but the guest's next reservations, removals and checkout on that session
-- failed as concurrent changes until the sweeper expired one cart's holds. The database now holds the rule,
-- and JpaActiveOrderRepository turns this constraint's violation into ConcurrentReservationException, which
-- rolls back the losing reservation with its seats.
--
-- A plain unique key on session_id does not fit: a member cart keeps the id of the session it is attached
-- to, so a guest cart and a member cart may share one. A partial unique index (where user_id is null) would,
-- but H2 has none. So the key is on guest_session_id, which ActiveOrder fills with the session id while the
-- cart has no user and clears once a member owns it. A member cart's guest_session_id is null, and NULLs are
-- distinct in a unique constraint on both PostgreSQL and H2, so member carts never collide on it.
--
-- Two guest carts of one session, written by that race before this migration, fail the last statement, and
-- Flyway stops the boot. Release the older cart's lines and delete it, then boot again. Like V7, this
-- refuses rather than keying one cart of the pair and leaving the other to the sweeper: a pair needs two
-- tabs racing within one hold length of the deploy, and a refusal shows it instead of hiding it.
--
-- Expand-only: code from before this migration never writes guest_session_id. While such an instance still
-- serves traffic on this schema, the guest carts it writes have a null guest_session_id, so the key protects
-- them only once code from this release rewrites them or the sweeper removes them. Such an instance also
-- leaves the key on a guest cart it gives a member at login, until code from this release rewrites that cart.
-- That blocks no one: the login keeps the session's id for the member's session, and no guest cart is made
-- on a member's session.

alter table active_orders add column guest_session_id varchar(255);

update active_orders set guest_session_id = session_id where user_id is null;

alter table active_orders add constraint uk_active_orders_guest_session_id unique (guest_session_id);
