package studioforyou.pl.backend.scheduling.internal;

/**
 * Who sees an event in the studio's schedule ({@code schedule_event.visibility}).
 */
enum EventVisibility {

	/** Shown to clients in the studio's schedule. */
	PUBLIC,

	/** Shown only to the people involved and the studio staff, e.g. a personal training session. */
	PRIVATE

}
