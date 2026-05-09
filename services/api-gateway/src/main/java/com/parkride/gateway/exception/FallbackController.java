package com.parkride.gateway.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Internal fallback endpoint invoked by the circuit-breaker filter
 * ({@code fallbackUri: forward:/fallback}).
 *
 * <p>Always returns {@code 503 Service Unavailable} with a
 * {@code application/problem+json} body. The {@link GatewayExceptionHandler}
 * is <em>not</em> needed here because we throw a {@link ResponseStatusException}
 * that Spring WebFlux serialises natively; the problem+json body is produced
 * by the exception handler for consistency.
 */
@RestController
public class FallbackController {

    @GetMapping(value = "/fallback", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<String> fallback() {
        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The upstream service is temporarily unavailable. Please try again shortly.");
    }
}
