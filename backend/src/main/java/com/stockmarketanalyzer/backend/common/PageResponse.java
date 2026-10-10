package com.stockmarketanalyzer.backend.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Stable, serialisation-friendly wrapper around a Spring Data {@link Page}.
 *
 * <p>Deliberately a record rather than a Lombok POJO: it is an immutable value with no
 * mutable state to generate.
 */
@Schema(name = "PageResponse", description = "A page of results plus the paging metadata.")
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean first, boolean last) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
