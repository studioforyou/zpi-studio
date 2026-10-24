package studioforyou.pl.backend.shared.api;

import java.util.List;

/**
 * Body of a non-paginated list response: {@code { "items": [...] }}.
 */
public record ListResponse<T>(List<T> items) {
}
