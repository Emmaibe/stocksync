package com.lordibe.stocksync.shared.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The shape every list endpoint returns.
 *
 * <p>Deliberately our own record rather than a serialised Spring Data
 * {@link Page}: Spring documents that {@code Page}'s JSON structure is not a
 * stable contract and warns about serialising it directly, so clients built
 * against it break on a framework upgrade. This shape changes when we change it.
 *
 * @param page zero-based, matching the request parameter
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages(),
                source.hasNext());
    }
}
