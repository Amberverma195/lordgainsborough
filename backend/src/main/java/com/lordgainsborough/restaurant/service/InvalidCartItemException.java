package com.lordgainsborough.restaurant.service;

/** Stable public validation failure; never includes submitted item contents or credentials. */
public final class InvalidCartItemException extends IllegalArgumentException {
    public InvalidCartItemException() { super("One or more cart items are invalid."); }
}
