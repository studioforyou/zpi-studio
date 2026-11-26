package studioforyou.pl.backend.integrations.calendar;

/**
 * Which part of an account's schedule a feed shows. An account that is both a client and a trainer
 * can subscribe to both parts in one calendar or to each part separately.
 */
enum CalendarFeedRole {

	/** The account's reservations and those of the children it is a guardian of. */
	CLIENT,

	/** The events the account's staff profile runs or assists. */
	TRAINER

}
