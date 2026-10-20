package com.xeats.backend.ordering;

/** Generic redemption failure that does not disclose whether a code exists at another counter. */
public final class InvalidPickupTokenException extends RuntimeException {
    public InvalidPickupTokenException() {
        super("This pickup token is invalid or unavailable.");
    }
}
