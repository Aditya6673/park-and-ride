package com.parkride.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter that ensures every request carries a {@code X-Trace-Id} header.
 *
 * <ul>
 *   <li>If {@code X-Trace-Id} is already present it is propagated unchanged.</li>
 *   <li>Otherwise a new UUID is generated and injected into both the downstream
 *       request and the response so callers can correlate log lines end-to-end.</li>
 * </ul>
 *
 * <p>Runs with {@link Ordered#HIGHEST_PRECEDENCE} so every subsequent filter
 * (including {@code AuthenticationFilter}) can log the trace ID.
 */
@Slf4j
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String TRACE_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        final String finalTraceId = traceId;

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(TRACE_HEADER, finalTraceId)
                .build();

        // Set BEFORE chain.filter() — response headers must be written before commit.
        // doOnSuccess() runs after the response is already sent and would be a no-op
        // (or cause a 500) on committed responses.
        exchange.getResponse().getHeaders().set(TRACE_HEADER, finalTraceId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
