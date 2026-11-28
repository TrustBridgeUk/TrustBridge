package com.trustbridge.Security.Actor;

/**
 * The only message headers a state machine guard is allowed to read.
 *
 * None of these is a yes/no answer. Each one is an identity or a reference that the guard
 * looks up and checks against the database or the payment provider itself.
 */
public final class GuardHeaders {

    /** UUID of the logged-in user, taken from the authenticated request. */
    public static final String ACTOR_ID = "actorId";

    /** Review token from the client's emailed link (X-Review-Token). Checked against the latest submission. */
    public static final String REVIEW_TOKEN = "reviewToken";

    private GuardHeaders() {
    }
}