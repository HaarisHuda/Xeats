package com.xeats.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void invalidFieldsReturnSafeStructuredProblemDetails() throws Exception {
        TestPayload payload = new TestPayload("");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(payload, "payload");
        bindingResult.addError(new FieldError("payload", "name", "", false, null, null, "must not be blank"));
        Method endpoint = getClass().getDeclaredMethod("endpoint", TestPayload.class);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(endpoint, 0), bindingResult);

        var response = handler.handleInvalidRequest(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getType().toString()).isEqualTo("urn:xeats:problem:validation-error");
        assertThat(body.getProperties()).containsEntry("code", "VALIDATION_ERROR");
        assertThat(body.getProperties()).containsKey("fieldErrors");
        assertThat(body.getDetail()).doesNotContain("\"");
    }

    @Test
    void unexpectedFailuresDoNotExposeExceptionMessages() {
        var response = handler.handleUnexpectedFailure(new IllegalStateException("sensitive request content"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail()).doesNotContain("sensitive request content");
    }

    @SuppressWarnings("unused")
    private void endpoint(TestPayload payload) {
    }

    private record TestPayload(String name) {
    }
}
