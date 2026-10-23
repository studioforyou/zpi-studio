package studioforyou.pl.backend.shared.api;

import java.util.List;
import java.util.function.Function;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of a growing list, as described in the API conventions.
 *
 * @param page the 0-based page number
 * @param size the requested page size
 * @param totalItems the number of items on all pages
 */
@Schema(description = "One page of a list.")
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
		int totalPages = (size > 0) ? (int) ((totalItems + size - 1) / size) : 0;
		return new PageResponse<>(items, page, size, totalItems, totalPages);
	}

	public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
		return new PageResponse<>(items.stream().<R>map(mapper).toList(), page, size, totalItems, totalPages);
	}

}
