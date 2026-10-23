package studioforyou.pl.backend.shared.api;

import org.springframework.http.HttpStatus;

/**
 * API error codes, each with the HTTP status it is returned with.
 * API error codes, each with the HTTP status it is returned with.
 */
public enum ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	ACCESS_DENIED(HttpStatus.FORBIDDEN),
	ACCOUNT_NOT_FOUND(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
	VERSION_CONFLICT(HttpStatus.CONFLICT),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

	// customer
	CUSTOMER_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),

	// offering
	PASS_VARIANT_ALREADY_EXISTS(HttpStatus.CONFLICT),
	PASS_PRODUCT_ARCHIVED(HttpStatus.UNPROCESSABLE_CONTENT),
	PASS_VARIANT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
	SERVICE_TYPE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	// membership
	PASS_AUDIENCE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

}
