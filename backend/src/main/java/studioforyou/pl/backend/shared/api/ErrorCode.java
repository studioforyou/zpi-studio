package studioforyou.pl.backend.shared.api;

import org.springframework.http.HttpStatus;

/**
 * API error codes, each with the HTTP status it is returned with.
 */
public enum ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	ACCESS_DENIED(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
	VERSION_CONFLICT(HttpStatus.CONFLICT),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

	// scheduling: rooms and the service types allowed in them
	RESOURCE_NAME_ALREADY_USED(HttpStatus.CONFLICT),
	RESOURCE_PARENT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
	SERVICE_TYPE_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

}
