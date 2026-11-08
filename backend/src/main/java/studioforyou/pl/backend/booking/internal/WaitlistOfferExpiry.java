package studioforyou.pl.backend.booking.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;

/**
 * Passes on the places of waiting-list offers nobody confirmed in time, also when nobody opens the
 * class. Every operation on the class does the same under its occurrence lock, so this only
 * catches up on quiet classes.
 */
@Component
class WaitlistOfferExpiry {

	private static final Logger log = LoggerFactory.getLogger(WaitlistOfferExpiry.class);

	private final WaitlistStore waitlist;

	private final GroupClassSchedule schedule;

	private final WaitlistPromotion promotion;

	private final Clock clock = Clock.systemUTC();

	WaitlistOfferExpiry(WaitlistStore waitlist, GroupClassSchedule schedule, WaitlistPromotion promotion) {
		this.waitlist = waitlist;
		this.schedule = schedule;
		this.promotion = promotion;
	}

	/** Events whose offers ran out; each is settled in its own transaction by {@link #settle}. */
	List<UUID> eventsToSettle() {
		return this.waitlist.eventsWithExpiredOffers(this.clock.instant());
	}

	/** Takes the occurrence lock like any change of the class, then gives the freed places again. */
	@Transactional
	public void settle(UUID eventId) {
		Instant now = this.clock.instant();
		Occurrence event;
		try {
			event = this.schedule.lock(eventId);
		}
		catch (ApiException ex) {
			// No longer a public class: nobody can book it, so its offers just close.
			this.waitlist.expireOffers(eventId, now);
			return;
		}
		this.promotion.fillFreePlaces(event, null, now, List.of());
	}

	/** Runs the periodic settling; switched off with {@code booking.waitlist.offer-expiry.enabled=false}. */
	@Configuration(proxyBeanMethods = false)
	@EnableScheduling
	@ConditionalOnProperty(name = "booking.waitlist.offer-expiry.enabled", havingValue = "true", matchIfMissing = true)
	static class Schedule {

		private final WaitlistOfferExpiry expiry;

		Schedule(WaitlistOfferExpiry expiry) {
			this.expiry = expiry;
		}

		@Scheduled(fixedDelayString = "${booking.waitlist.offer-expiry.interval:PT1M}")
		void run() {
			for (UUID eventId : this.expiry.eventsToSettle()) {
				try {
					this.expiry.settle(eventId);
				}
				catch (RuntimeException ex) {
					log.warn("Could not pass on the expired waiting-list offers of event {}", eventId, ex);
				}
			}
		}

	}

}
