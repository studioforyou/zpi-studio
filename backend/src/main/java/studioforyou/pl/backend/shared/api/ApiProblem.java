package studioforyou.pl.backend.shared.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;


@Schema(name = "ApiProblem", description = "Error response: RFC 9457 Problem Details with a stable code.")
public record ApiProblem(
		@Schema(example = "about:blank") String type,
		@Schema(example = "Not Found") String title,
		@Schema(example = "404") int status,
		@Schema(example = "Studio settings are not configured.") String detail,
		@Schema(example = "/api/v1/studio") String instance,
		@Schema(example = "NOT_FOUND") String code,
		@Schema(description = "Present only for VALIDATION_FAILED.") List<FieldError> errors) {

	/**
	 * One failed field of a {@code VALIDATION_FAILED} response.
	 */
	@Schema(name = "ApiFieldError")
	public record FieldError(
			@Schema(example = "eventId") String field,
			@Schema(example = "REQUIRED") FieldErrorCode code,
			@Schema(example = "must not be null") String message) {
	}

}
