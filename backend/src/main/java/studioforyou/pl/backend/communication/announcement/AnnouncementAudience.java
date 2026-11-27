package studioforyou.pl.backend.communication.announcement;

/** Who an announcement is for ({@code announcement.audience}). */
public enum AnnouncementAudience {

	/** One person with an account: a client or a trainer. */
	PERSON,

	/** The current members of a training group and the participants booked on its upcoming classes. */
	GROUP,

	/** The participants booked on one class. */
	EVENT,

	/** Every active client. */
	ALL

}
