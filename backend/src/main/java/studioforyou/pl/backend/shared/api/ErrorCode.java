package studioforyou.pl.backend.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Generic API error codes, each with the HTTP status it is returned with.
 */
public enum ErrorCode {

	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	ACCESS_DENIED(HttpStatus.FORBIDDEN),
	ACCOUNT_NOT_FOUND(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND),
	INVITATION_EXPIRED(HttpStatus.GONE),
	INVITATION_ALREADY_USED(HttpStatus.GONE),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
	VERSION_CONFLICT(HttpStatus.CONFLICT),
	ACCOUNT_ALREADY_EXISTS(HttpStatus.CONFLICT),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
	EMAIL_NOT_SENT(HttpStatus.BAD_GATEWAY);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

}
