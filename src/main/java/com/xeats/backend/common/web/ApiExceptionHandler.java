package com.xeats.backend.common.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(MethodArgumentNotValidException exception) {
        List<ApiFieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(),
                        error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage()))
                .distinct()
                .sorted(Comparator.comparing(ApiFieldError::field).thenComparing(ApiFieldError::message))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                "One or more request fields are invalid.");
        problem.setProperty("fieldErrors", errors);
        return response(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApiProblem(ApiProblemException exception) {
        ProblemDetail problem = problem(exception.status(), exception.code(), exception.getMessage());
        if (exception.refreshedOrderOptions() != null) {
            problem.setProperty("refreshedOrderOptions", exception.refreshedOrderOptions());
        }
        if (exception.currentOrder() != null) {
            problem.setProperty("currentOrder", exception.currentOrder());
        }
        return response(exception.status(), problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        List<ApiFieldError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiFieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .distinct()
                .sorted(Comparator.comparing(ApiFieldError::field).thenComparing(ApiFieldError::message))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                "One or more request values are invalid.");
        problem.setProperty("fieldErrors", errors);
        return response(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestValueException.class,
            TypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleUnreadableOrMissingRequestValue(Exception exception) {
        return respond(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR,
                "The request body or a required request value is invalid.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiErrorCode.METHOD_NOT_ALLOWED,
                "The requested method is not supported for this resource.");
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ProblemDetail> handleNotFound(Exception exception) {
        return respond(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, "The requested resource was not found.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpectedFailure(Exception exception) {
        LOGGER.error("Unhandled request failure ({})", exception.getClass().getName());
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "An unexpected server error occurred.");
    }

    private ResponseEntity<ProblemDetail> respond(HttpStatusCode status, ApiErrorCode code, String detail) {
        return response(status, problem(status, code, detail));
    }

    private ProblemDetail problem(HttpStatusCode status, ApiErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        problem.setType(URI.create("urn:xeats:problem:" + code.name().toLowerCase().replace('_', '-')));
        problem.setProperty("code", code.name());
        return problem;
    }

    private ResponseEntity<ProblemDetail> response(HttpStatusCode status, ProblemDetail problem) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
