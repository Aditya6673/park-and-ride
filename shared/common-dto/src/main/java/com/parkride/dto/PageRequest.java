package com.parkride.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Pagination contracts for list endpoints across all services.
 *
 * <p>Contains two types:
 * <ul>
 *   <li>{@link PageRequest}  — inbound query parameters from the caller</li>
 *   <li>{@link PagedResponse} — outbound paginated payload returned to the caller</li>
 * </ul>
 *
 * <p>Design note — offset vs. cursor pagination:
 * Phase 1 uses offset pagination (page + size) because booking history
 * volumes are low, and it maps naturally to Spring Data's {@code Pageable}.
 * Phase 3 will migrate high-volume endpoints (slot events, analytics) to
 * cursor-based pagination to avoid the N+offset DB scan problem at scale.
 * See ADR-004 for the documented trade-off.
 */
public final class PageRequest {

    // Prevent instantiation — this class is a namespace for the two nested types.
    private PageRequest() {}

    // ── Inbound request parameters ────────────────────────────────────────

    /**
     * Query parameters for paginated list endpoints.
     *
     * <p>Controller usage (Spring MVC model attribute binding):
     * <pre>
     * {@code
     * @GetMapping("/bookings")
     * public ResponseEntity<ApiResponse<PagedResponse<BookingResponse>>> list(
     *         @Valid PageRequest.Query query) { ... }
     * }
     * </pre>
     *
     * <p>Defaults to page 0, 20 items per page, sorted by {@code createdAt} descending —
     * safe defaults that work for every list endpoint without explicit configuration.
     */
    @Getter
    @Builder
    public static final class Query {

        /** Zero-based page index. Validated to prevent negative values. */
        @Min(value = 0, message = "Page index must be 0 or greater")
        @Builder.Default
        @JsonProperty("page")
        private final int page = 0;

        /**
         * Number of records per page. Capped at 100 to prevent runaway queries
         * that could cause OOM or expose excessive data in a single response.
         */
        @Min(value = 1,   message = "Page size must be at least 1")
        @Max(value = 100, message = "Page size must not exceed 100")
        @Builder.Default
        @JsonProperty("size")
        private final int size = 20;

        /**
         * Field name to sort by. Validated against an allowlist in each service's
         * controller to prevent SQL injection via sort parameter.
         * Defaults to {@code createdAt} — a safe, always-present field.
         */
        @Builder.Default
        @JsonProperty("sortBy")
        private final String sortBy = "createdAt";

        /**
         * Sort direction. Accepted values: {@code asc}, {@code desc}.
         * Default descending — most recent records first, which is the
         * expected behaviour for booking history and notifications.
         */
        @Builder.Default
        @JsonProperty("sortDir")
        private final String sortDir = "desc";

        /** Convenience: is this a descending sort? */
        public boolean isDescending() {
            return "desc".equalsIgnoreCase(sortDir);
        }
    }

    // ── Outbound paged response ───────────────────────────────────────────

    /**
     * Wraps a list of items with metadata required by the frontend to render
     * pagination controls (total pages, total items, current page indicator).
     *
     * <p>JSON shape:
     * <pre>
     * {
     *   "content": [ { ... }, { ... } ],
     *   "page": 0,
     *   "size": 20,
     *   "totalElements": 87,
     *   "totalPages": 5,
     *   "first": true,
     *   "last": false
     * }
     * </pre>
     *
     * @param <T> the item type inside the page
     */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PagedResponse<T> {

        /** The records for this page. */
        @JsonProperty("content")
        private final List<T> content;

        /** Current zero-based page index. */
        @JsonProperty("page")
        private final int page;

        /** Items per page as requested. */
        @JsonProperty("size")
        private final int size;

        /** Total number of matching records across all pages. */
        @JsonProperty("totalElements")
        private final long totalElements;

        /** Total number of pages: {@code ceil(totalElements / size)}. */
        @JsonProperty("totalPages")
        private final int totalPages;

        /** {@code true} if this is the first page (page == 0). */
        @JsonProperty("first")
        private final boolean first;

        /** {@code true} if this is the last page. */
        @JsonProperty("last")
        private final boolean last;

        // ── Factory — converts a Spring Data Page to PagedResponse ────────

        /**
         * Builds a {@code PagedResponse<T>} from a Spring Data {@code Page<T>}.
         *
         * <p>Decouples the shared DTO from a direct Spring Data dependency by
         * accepting primitives. Service layers extract what they need from the
         * {@code Page} object and pass it here.
         *
         * <p>Example usage in a service:
         * <pre>
         * {@code
         * Page<BookingResponse> page = bookingRepository.findByUserId(userId, pageable)
         *         .map(bookingMapper::toResponse);
         *
         * return PageRequest.PagedResponse.from(
         *         page.getContent(),
         *         page.getNumber(),
         *         page.getSize(),
         *         page.getTotalElements(),
         *         page.getTotalPages()
         * );
         * }
         * </pre>
         */
        public static <T> PagedResponse<T> from(
                List<T> content,
                int     page,
                int     size,
                long    totalElements,
                int     totalPages) {

            return PagedResponse.<T>builder()
                    .content(content)
                    .page(page)
                    .size(size)
                    .totalElements(totalElements)
                    .totalPages(totalPages)
                    .first(page == 0)
                    .last(page >= totalPages - 1)
                    .build();
        }
    }
}