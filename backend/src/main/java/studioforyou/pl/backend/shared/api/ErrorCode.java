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

	// identity: account management by administrators
	/** The account's status does not allow the change, e.g. suspending an account that is not active. */
	ACCOUNT_STATUS_CONFLICT(HttpStatus.CONFLICT),
	/** Administrators cannot suspend or close their own account. */
	OWN_ACCOUNT_CHANGE(HttpStatus.UNPROCESSABLE_CONTENT),

	// customer
	CUSTOMER_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
	/** The trainer already has an active assignment to the participant. */
	TRAINER_ALREADY_ASSIGNED(HttpStatus.CONFLICT),
	/** The staff member's account does not have the TRAINER role. */
	STAFF_NOT_TRAINER(HttpStatus.UNPROCESSABLE_CONTENT),

	// documents
	DOCUMENT_ALREADY_ACCEPTED(HttpStatus.CONFLICT),
	/** A required document cannot be withdrawn by the participant, only an optional consent. */
	DOCUMENT_REVOCATION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),

	// staff
	/** The staff member already has availability on that day at an overlapping time. */
	AVAILABILITY_OVERLAP(HttpStatus.CONFLICT),

	// offering
	PASS_VARIANT_ALREADY_EXISTS(HttpStatus.CONFLICT),
	PASS_PRODUCT_ARCHIVED(HttpStatus.UNPROCESSABLE_CONTENT),
	PASS_VARIANT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
	SERVICE_TYPE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	// membership
	PASS_AUDIENCE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
	PASS_NOT_USABLE(HttpStatus.UNPROCESSABLE_CONTENT),
	PASS_ENTRIES_EXHAUSTED(HttpStatus.UNPROCESSABLE_CONTENT),
	PASS_SERVICE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
	/** The product does not allow suspension, or the suspension breaks its rules. */
	PASS_SUSPENSION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),

	// payments
	/** The order is not waiting for a payment, e.g. it has already been paid. */
	ORDER_NOT_PAYABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	// booking: client group-class participation
	EVENT_NOT_BOOKABLE(HttpStatus.UNPROCESSABLE_CONTENT),
	BOOKING_NOT_OPEN(HttpStatus.UNPROCESSABLE_CONTENT),
	BOOKING_CLOSED(HttpStatus.UNPROCESSABLE_CONTENT),
	CLASS_FULL(HttpStatus.CONFLICT),
	AGE_RESTRICTION(HttpStatus.UNPROCESSABLE_CONTENT),
	GROUP_MEMBERSHIP_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
	REQUIRED_DOCUMENTS_MISSING(HttpStatus.UNPROCESSABLE_CONTENT),
	NO_ELIGIBLE_PASS(HttpStatus.UNPROCESSABLE_CONTENT),
	CANCELLATION_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),
	FREE_CANCELLATION_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT),
	EVENT_ALREADY_STARTED(HttpStatus.UNPROCESSABLE_CONTENT),
	RESERVATION_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
	CANCELLATION_TERMS_UNAVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT),
	CANCELLATION_CONSEQUENCE_CHANGED(HttpStatus.CONFLICT),

	// booking: attendance
	/** Attendance is recorded only once the event has started. */
	ATTENDANCE_NOT_OPEN(HttpStatus.UNPROCESSABLE_CONTENT),
	/** The trainer's time to record attendance is over; an administrator corrects it from then on. */
	ATTENDANCE_CLOSED(HttpStatus.UNPROCESSABLE_CONTENT),
	/** A cancelled event has no attendance to record. */
	EVENT_CANCELLED(HttpStatus.UNPROCESSABLE_CONTENT),
	// booking: waiting list of a full group class
	/** The participant already has an active reservation for the class. */
	ALREADY_RESERVED(HttpStatus.CONFLICT),
	/** The class still has free places, so the participant books one instead of waiting. */
	CLASS_NOT_FULL(HttpStatus.CONFLICT),
	WAITLIST_DISABLED(HttpStatus.UNPROCESSABLE_CONTENT),
	/** The participant is no longer waiting: they got a place or the entry was closed. */
	WAITLIST_ENTRY_NOT_WAITING(HttpStatus.UNPROCESSABLE_CONTENT),
	/** No place is offered to the participant to confirm. */
	WAITLIST_ENTRY_NOT_OFFERED(HttpStatus.UNPROCESSABLE_CONTENT),
	/** The time to confirm the offered place has run out; it goes to the next participant. */
	WAITLIST_OFFER_EXPIRED(HttpStatus.CONFLICT),
	// booking: places booked by administrators
	/**
	 * The class is full or the participant does not meet a requirement the administrator has not
	 * confirmed overriding; the {@code issues} property lists all of them.
	 */
	BOOKING_OVERRIDE_REQUIRED(HttpStatus.CONFLICT),

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
	EVENT_NOT_EDITABLE(HttpStatus.UNPROCESSABLE_CONTENT),

	// identity: roles and permissions of accounts
	ROLE_ALREADY_GRANTED(HttpStatus.CONFLICT),
	PERMISSION_OVERRIDE_EXISTS(HttpStatus.CONFLICT),
	UNKNOWN_ROLE(HttpStatus.UNPROCESSABLE_CONTENT),
	UNKNOWN_PERMISSION(HttpStatus.UNPROCESSABLE_CONTENT),
	LAST_ACTIVE_ADMIN(HttpStatus.UNPROCESSABLE_CONTENT),

	// communication: conversations and messages
	CONVERSATION_ALREADY_EXISTS(HttpStatus.CONFLICT),
	CONVERSATION_READ_ONLY(HttpStatus.UNPROCESSABLE_CONTENT),
	CONVERSATION_STILL_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
	RECIPIENT_WITHOUT_ACCOUNT(HttpStatus.UNPROCESSABLE_CONTENT);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus getStatus() {
		return this.status;
	}

}
