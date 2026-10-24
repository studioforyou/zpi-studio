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
	EMAIL_NOT_SENT(HttpStatus.BAD_GATEWAY),

	// customer
	CUSTOMER_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),

	// offering
	PASS_VARIANT_ALREADY_EXISTS(HttpStatus.CONFLICT),
	PASS_PRODUCT_ARCHIVED(HttpStatus.UNPROCESSABLE_CONTENT),
	PASS_VARIANT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
	SERVICE_TYPE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	// membership
	PASS_AUDIENCE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),

	// scheduling: rooms and the service types allowed in them
	RESOURCE_NAME_ALREADY_USED(HttpStatus.CONFLICT),
	RESOURCE_PARENT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),

	// scheduling: events, recurring series and their staff and resources
	/** A room is already taken at that time. */
	RESOURCE_CAPACITY_EXCEEDED(HttpStatus.CONFLICT),
	/** A staff member already runs another event at that time or is on approved leave. */
	STAFF_SCHEDULE_CONFLICT(HttpStatus.CONFLICT),
	RESOURCE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),
	STAFF_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),
	SERVICE_NOT_ALLOWED_IN_ROOM(HttpStatus.UNPROCESSABLE_CONTENT),
	EVENT_NOT_EDITABLE(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

}
