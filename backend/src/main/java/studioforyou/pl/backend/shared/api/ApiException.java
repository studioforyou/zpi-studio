package studioforyou.pl.backend.shared.api;

import java.util.List;

/**
 * Exception that is returned to the client as an error response with the given code.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	private final List<ApiProblem.FieldError> fieldErrors;

	public ApiException(ErrorCode errorCode, String detail) {
		this(errorCode, detail, List.of());
	}

	private ApiException(ErrorCode errorCode, String detail, List<ApiProblem.FieldError> fieldErrors) {
		super(detail);
		this.errorCode = errorCode;
		this.fieldErrors = fieldErrors;
	}

	/**
	 * A {@code VALIDATION_FAILED} error for a field whose value is only known to be wrong
	 * after looking at stored data, e.g. a date that does not fit the chosen pass variant.
	 */
	public static ApiException invalidField(String field, FieldErrorCode code, String message) {
		return new ApiException(ErrorCode.VALIDATION_FAILED, message,
				List.of(new ApiProblem.FieldError(field, code, message)));
	}

	public ErrorCode getErrorCode() {
		return this.errorCode;
	}

	/** The failed fields of a {@code VALIDATION_FAILED} error; empty for other codes. */
	public List<ApiProblem.FieldError> getFieldErrors() {
		return this.fieldErrors;
	}

}
