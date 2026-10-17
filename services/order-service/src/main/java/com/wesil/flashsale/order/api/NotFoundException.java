package com.wesil.flashsale.order.api;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super(what + " not found");
    }
}
