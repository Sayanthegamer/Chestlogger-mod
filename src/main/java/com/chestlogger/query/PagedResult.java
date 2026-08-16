package com.chestlogger.query;

import java.util.Collections;
import java.util.List;

/**
 * Generic container for paginated query results.
 */
public record PagedResult<T>(
        List<T> items,
        int pageNumber,
        int pageSize,
        int totalPages,
        int totalElements
) {
    public static <T> PagedResult<T> of(List<T> allItems, int pageNumber, int pageSize) {
        if (allItems == null || allItems.isEmpty()) {
            return new PagedResult<>(Collections.emptyList(), Math.max(1, pageNumber), pageSize, 1, 0);
        }
        int total = allItems.size();
        int pages = Math.max(1, (int) Math.ceil((double) total / pageSize));
        if (pageNumber < 1 || pageNumber > pages) {
            return new PagedResult<>(Collections.emptyList(), pageNumber, pageSize, pages, total);
        }
        int fromIndex = (pageNumber - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<T> subList = allItems.subList(fromIndex, toIndex);

        return new PagedResult<>(subList, pageNumber, pageSize, pages, total);
    }
}
