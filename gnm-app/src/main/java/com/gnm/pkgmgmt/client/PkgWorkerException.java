package com.gnm.pkgmgmt.client;

public class PkgWorkerException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;
    private final String actualFingerprint;
    private final Integer exitCode;

    public PkgWorkerException(String errorCode, String message, int httpStatus) {
        this(errorCode, message, httpStatus, null, null);
    }

    public PkgWorkerException(
            String errorCode,
            String message,
            int httpStatus,
            String actualFingerprint,
            Integer exitCode) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.actualFingerprint = actualFingerprint;
        this.exitCode = exitCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String actualFingerprint() {
        return actualFingerprint;
    }

    public Integer exitCode() {
        return exitCode;
    }
}

