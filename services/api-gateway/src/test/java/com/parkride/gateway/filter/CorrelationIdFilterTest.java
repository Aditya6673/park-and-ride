package com.parkride.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorrelationIdFilter — unit tests")
class CorrelationIdFilterTest {

    @Mock GatewayFilterChain chain;

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("No incoming X-Trace-Id → new UUID generated and forwarded downstream")
    void noTraceId_generatesNewUuid() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/test").build());

        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        // Verify the chain received a request with the generated trace ID
        var captor = org.mockito.ArgumentCaptor.forClass(
                org.springframework.web.server.ServerWebExchange.class);
        org.mockito.Mockito.verify(chain).filter(captor.capture());

        String traceId = captor.getValue().getRequest()
                .getHeaders().getFirst(CorrelationIdFilter.TRACE_HEADER);
        assertThat(traceId).isNotNull().isNotBlank();
        // Should be a valid UUID format
        assertThat(traceId).matches("[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("Existing X-Trace-Id → same ID propagated, not overwritten")
    void existingTraceId_propagatedUnchanged() {
        String existing = "my-existing-trace-id-12345";
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/test")
                        .header(CorrelationIdFilter.TRACE_HEADER, existing)
                        .build());

        given(chain.filter(any())).willReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        var captor = org.mockito.ArgumentCaptor.forClass(
                org.springframework.web.server.ServerWebExchange.class);
        org.mockito.Mockito.verify(chain).filter(captor.capture());

        String traceId = captor.getValue().getRequest()
                .getHeaders().getFirst(CorrelationIdFilter.TRACE_HEADER);
        assertThat(traceId).isEqualTo(existing);
    }
}
