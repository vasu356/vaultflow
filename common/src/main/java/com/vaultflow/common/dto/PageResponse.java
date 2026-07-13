package com.vaultflow.common.dto;

import java.util.List;

/**
 * Generic paginated response wrapper. Services return this for any list endpoint. Keeps pagination
 * metadata consistent across the entire platform API surface.
 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean first,
    boolean last) {

  public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
    int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(
        content, page, size, totalElements, totalPages, page == 0, page >= totalPages - 1);
  }
}
