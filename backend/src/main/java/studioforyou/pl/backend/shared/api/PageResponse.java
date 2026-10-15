package studioforyou.pl.backend.shared.api;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Response body of a paginated list. Spring's {@link Page} is never serialized directly.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

	public PageResponse {
		items = List.copyOf(items);
	}

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
