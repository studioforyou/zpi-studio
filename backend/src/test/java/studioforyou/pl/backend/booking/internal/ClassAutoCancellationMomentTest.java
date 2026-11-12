package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.configuration.BookingPolicies.Values;

/** When the automatic cancellation counts the participants of a class. */
class ClassAutoCancellationMomentTest {

	private static final Instant START = Instant.parse("2027-01-12T17:00:00Z");

	@Test
	void countsAtTheHoursBeforeTheStartSetInThePolicy() {
		assertThat(DefaultClassAutoCancellation.checkMoment(START, rules(true, 3, 30)))
			.isEqualTo(START.minus(Duration.ofHours(3)));
	}

	@Test
	void withoutThoseHoursCountsWhenBookingCloses() {
		assertThat(DefaultClassAutoCancellation.checkMoment(START, rules(true, null, 90)))
			.isEqualTo(START.minus(Duration.ofMinutes(90)));
	}

	@Test
	void countsAtLeastAFewMinutesBeforeTheStart() {
		assertThat(DefaultClassAutoCancellation.checkMoment(START, rules(true, 0, 0)))
			.isEqualTo(START.minus(DefaultClassAutoCancellation.MIN_NOTICE));
		assertThat(DefaultClassAutoCancellation.checkMoment(START, rules(true, null, null)))
			.isEqualTo(START.minus(DefaultClassAutoCancellation.MIN_NOTICE));
	}

	@Test
	void isDueFromTheMomentForTheGracePeriodAndBeforeTheStart() {
		Instant moment = START.minus(Duration.ofHours(3));
		Values rules = rules(true, 3, 30);
		assertThat(DefaultClassAutoCancellation.isDue(START, rules, moment.minusSeconds(1))).isFalse();
		assertThat(DefaultClassAutoCancellation.isDue(START, rules, moment)).isTrue();
		assertThat(DefaultClassAutoCancellation.isDue(START, rules, moment.plus(Duration.ofMinutes(29)))).isTrue();
		assertThat(DefaultClassAutoCancellation.isDue(START, rules, moment.plus(DefaultClassAutoCancellation.GRACE)))
			.isFalse();
	}

	@Test
	void isNeverDueWhenTheRuleIsOff() {
		assertThat(DefaultClassAutoCancellation.isDue(START, rules(false, 3, 30), START.minus(Duration.ofHours(3))))
			.isFalse();
	}

	private static Values rules(boolean enabled, Integer hoursBefore, Integer bookingClosesMinutesBefore) {
		return new Values(true, 12, true, true, 14, bookingClosesMinutesBefore, true, 0, enabled, 3, hoursBefore);
	}

}
