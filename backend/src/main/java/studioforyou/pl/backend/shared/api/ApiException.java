package studioforyou.pl.backend.shared.api;

import java.util.List;
import java.util.Map;

/**
 * Exception that is returned to the client as an error response with the given code.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	private final List<ApiProblem.FieldError> fieldErrors;

	private final Map<String, Object> properties;

	public ApiException(ErrorCode errorCode, String detail) {
		this(errorCode, detail, List.of(), Map.of());
	}

	/**
	 * An error whose response carries extra top-level properties next to {@code code}, e.g. the
	 * list of conflicting events that made a schedule change impossible.
	 */
	public ApiException(ErrorCode errorCode, String detail, Map<String, Object> properties) {
		this(errorCode, detail, List.of(), properties);
	}

	private ApiException(ErrorCode errorCode, String detail, List<ApiProblem.FieldError> fieldErrors,
			Map<String, Object> properties) {
		super(detail);
		this.errorCode = errorCode;
		this.fieldErrors = fieldErrors;
		this.properties = Map.copyOf(properties);
	}

	/**
	 * A {@code VALIDATION_FAILED} error for a field whose value is only known to be wrong
	 * after looking at stored data, e.g. a date that does not fit the chosen pass variant.
	 */
	public static ApiException invalidField(String field, FieldErrorCode code, String message) {
		return new ApiException(ErrorCode.VALIDATION_FAILED, message,
				List.of(new ApiProblem.FieldError(field, code, message)), Map.of());
	}

	public ErrorCode getErrorCode() {
		return this.errorCode;
	}

	/** The failed fields of a {@code VALIDATION_FAILED} error; empty for other codes. */
	public List<ApiProblem.FieldError> getFieldErrors() {
		return this.fieldErrors;
	}

	/** Extra properties of the error response; empty for most errors. */
	public Map<String, Object> getProperties() {
		return this.properties;
	}

}
