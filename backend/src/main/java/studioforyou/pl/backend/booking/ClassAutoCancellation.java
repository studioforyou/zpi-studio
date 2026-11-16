package studioforyou.pl.backend.booking;

import java.time.Instant;

/**
 * The automatic cancellation of group classes too few participants booked. Each class is checked
 * once, when its booking policy says so: {@code autoCancelHoursBefore} before the start, or when
 * booking closes when that is not set. A class with fewer active reservations than
 * {@code autoCancelMinParticipants} is cancelled, unless an administrator forced it to run; its
 * reservations are cancelled with their entries returned and its waiting list is closed. The
 * notification job runs it every few minutes.
 */
public interface ClassAutoCancellation {

	/**
	 * Cancels the classes due for the check at {@code now} that have too few participants.
	 *
	 * @return how many classes were cancelled
	 */
	int cancelDue(Instant now);

}
