package com.parkride.pricing.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 */
class GlobalExceptionHandlerTest {

    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("NoSuchElementException → 404 ProblemDetail")
    void handleNotFound_returns404() {
        ProblemDetail pd = handler.handleNotFound(
                new NoSuchElementException("Rule not found: abc"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getDetail()).contains("Rule not found");
    }

    @Test
    @DisplayName("AccessDeniedException → 403 ProblemDetail")
    void handleAccessDenied_returns403() {
        ProblemDetail pd = handler.handleAccessDenied(
                new AccessDeniedException("Access is denied"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(pd.getDetail()).contains("permissions");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 ProblemDetail with field message")
    @SuppressWarnings("null") // null MethodParameter is acceptable in unit tests — Spring itself does this
    void handleValidation_returns400() throws Exception {
        // Build a fake binding result with one field error
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "baseRate", "baseRate must be greater than 0"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ProblemDetail pd = handler.handleValidation(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getDetail()).contains("baseRate must be greater than 0");
    }

    @Test
    @DisplayName("Unexpected exception → 500 ProblemDetail")
    void handleGeneric_returns500() {
        ProblemDetail pd = handler.handleGeneric(
                new RuntimeException("Unexpected Redis failure"));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getDetail()).contains("unexpected");
    }
}
