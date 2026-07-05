package com.paylane.ledger.repo;

public class UnknownAccountException extends RuntimeException {
    public UnknownAccountException(String code) {
        super("unknown account code: " + code);
    }
}
