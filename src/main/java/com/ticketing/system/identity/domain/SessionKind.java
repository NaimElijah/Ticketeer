package com.ticketing.system.identity.domain;

/**
 * Whose session a {@link Session} row is. Member ids and admin ids are drawn from separate pools
 * that share one integer space, so a row's user id alone cannot say whether it names a member or
 * an admin; the kind does (ADR-012).
 *
 * <ul>
 *   <li>{@link #GUEST}: an anonymous visitor. The row has no user id, and its raw session id is the
 *       credential.</li>
 *   <li>{@link #MEMBER}: a signed-in member. The user id is a member id, and the credential is a
 *       member-role JWT naming the row.</li>
 *   <li>{@link #ADMIN}: a signed-in system admin. The user id is an admin id, and the credential is
 *       an admin-role JWT naming the row.</li>
 * </ul>
 */
public enum SessionKind {
    GUEST,
    MEMBER,
    ADMIN
}
