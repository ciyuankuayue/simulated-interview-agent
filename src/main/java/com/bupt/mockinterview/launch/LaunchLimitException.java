package com.bupt.mockinterview.launch;

public class LaunchLimitException extends RuntimeException {
    private final int status;
    private final String code;
    public LaunchLimitException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
