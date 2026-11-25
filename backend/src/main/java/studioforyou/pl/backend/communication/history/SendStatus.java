package studioforyou.pl.backend.communication.history;

/** How far a sent notification got, from the best outcome down. */
public enum SendStatus {

	/** The recipient opened it in the app. */
	READ,

	/** A push reached at least one of the recipient's phones. */
	DELIVERED,

	/** Every push attempt failed; the notification waits in the app's inbox. */
	PUSH_FAILED,

	/** No push was sent (no registered phone, or skipped); it is only in the app's inbox. */
	APP_ONLY

}
