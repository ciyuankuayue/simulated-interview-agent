package com.bupt.mockinterview.operations;

public class OperationException extends RuntimeException {
    public final int status;
    public final String code;

    public OperationException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
