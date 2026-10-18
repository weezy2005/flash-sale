package com.wesil.flashsale.order.api;

/** The request is valid but cannot be applied to the current state (sold out, already released, ...). */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
