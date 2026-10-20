package com.xeats.backend.common.web;

import org.springframework.http.HttpStatusCode;

public final class ApiProblemException extends RuntimeException {
    private final HttpStatusCode status;
    private final ApiErrorCode code;
    private final Object refreshedOrderOptions;
    private final Object currentOrder;

    public ApiProblemException(HttpStatusCode status, ApiErrorCode code, String detail) {
        this(status, code, detail, null, null);
    }

    public ApiProblemException(
            HttpStatusCode status, ApiErrorCode code, String detail, Object refreshedOrderOptions) {
        this(status, code, detail, refreshedOrderOptions, null);
    }

    public ApiProblemException(
            HttpStatusCode status, ApiErrorCode code, String detail,
            Object refreshedOrderOptions, Object currentOrder) {
        super(detail);
        this.status = status;
        this.code = code;
        this.refreshedOrderOptions = refreshedOrderOptions;
        this.currentOrder = currentOrder;
    }

    public HttpStatusCode status() {
        return status;
    }

    public ApiErrorCode code() {
        return code;
    }

    public Object refreshedOrderOptions() {
        return refreshedOrderOptions;
    }

    public Object currentOrder() {
        return currentOrder;
    }
}
