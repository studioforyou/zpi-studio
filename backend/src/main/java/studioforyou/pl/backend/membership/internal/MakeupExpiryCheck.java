package studioforyou.pl.backend.membership.internal;

/**
 * What a make-up credit's expiry is compared with when it is used: the start of the class it
 * books ({@code EVENT_DATE}) or the moment the booking is made ({@code BOOK_DATE}).
 */
public enum MakeupExpiryCheck {

	EVENT_DATE, BOOK_DATE

}
