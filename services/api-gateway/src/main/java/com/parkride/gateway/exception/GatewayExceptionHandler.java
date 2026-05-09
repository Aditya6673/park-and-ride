package com.parkride.gateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Global exception handler for the reactive gateway.
 *
 * <p>Translates gateway-level exceptions (circuit breaker open, upstream not found,
 * rate limit exceeded, unhandled errors) into RFC 9457 {@code application/problem+json}
 * responses so API consumers always receive a consistent error envelope.
 *
 * <p>Ordered at {@code -2} to run before Spring Boot's default
 * {@code DefaultErrorWebExceptionHandler} (order {@code -1}).
 */
@Slf4j
@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        HttpStatus status;
        String     detail;

        if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
            detail = rse.getReason() != null ? rse.getReason() : rse.getMessage();
        } else if (ex instanceof NotFoundException) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            detail = "Upstream service is unavailable";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            detail = "An unexpected gateway error occurred";
        }

        log.warn("Gateway error [{}]: {}", status.value(), ex.getMessage());

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = buildProblemJson(status, detail);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var    buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private String buildProblemJson(HttpStatus status, String detail) {
        return """
               {"type":"about:blank","title":"%s","status":%d,"detail":"%s"}
               """.formatted(status.getReasonPhrase(), status.value(),
                             sanitize(detail)).strip();
    }

    /** Escape double-quotes so the JSON stays well-formed. */
    private String sanitize(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
