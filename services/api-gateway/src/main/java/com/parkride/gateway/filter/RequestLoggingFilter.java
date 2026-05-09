package com.parkride.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Global filter that logs every request/response in structured one-liners.
 *
 * <p>Log format:
 * <pre>
 * → GET /api/v1/parking/slots traceId=abc123
 * ← 200 GET /api/v1/parking/slots traceId=abc123 durationMs=42
 * </pre>
 *
 * <p>Runs at {@link Ordered#LOWEST_PRECEDENCE} so it wraps around all other filters
 * and can measure the true end-to-end latency seen at the gateway.
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request  = exchange.getRequest();
        String            method   = request.getMethod().name();
        String            path     = request.getPath().value();
        String            traceId  = request.getHeaders()
                                            .getFirst(CorrelationIdFilter.TRACE_HEADER);
        long              startMs  = Instant.now().toEpochMilli();

        log.info("→ {} {} traceId={}", method, path, traceId);

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = Instant.now().toEpochMilli() - startMs;
            int  status     = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;
            log.info("← {} {} {} traceId={} durationMs={}",
                    status, method, path, traceId, durationMs);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
