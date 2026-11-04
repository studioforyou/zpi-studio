package studioforyou.pl.backend.shared.api;

import java.util.List;

/**
 * Body of a list response that is not paginated: {@code {"items": [...]}}.
 */
public record ItemsResponse<T>(List<T> items) {

}
