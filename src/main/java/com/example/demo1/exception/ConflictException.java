package com.example.demo1.exception;

/**
 * The request is well-formed but conflicts with the current state of the data,
 * so it cannot be applied. Mapped to HTTP {@code 409 Conflict}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
