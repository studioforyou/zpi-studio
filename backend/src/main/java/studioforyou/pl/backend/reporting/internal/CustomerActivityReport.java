package studioforyou.pl.backend.reporting.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.identity.AccountStatistics;
import studioforyou.pl.backend.membership.PassHolders;
import studioforyou.pl.backend.scheduling.ScheduledEvents;

/**
 * Counts active and new customers in a period of studio dates.
 * <ul>
 * <li>An <em>active</em> customer is a participant (adult or child) who held a valid pass on at least
 * one day of the period, or had a reservation that was not cancelled for an event starting in it.</li>
 * <li>A <em>new</em> customer is a client account activated in the period, or a participant profile
 * without an account (e.g. a child) added in it.</li>
 * </ul>
 */
@Service
class CustomerActivityReport {

	private final StudioSettings studioSettings;

	private final PassHolders passHolders;

	private final ScheduledEvents scheduledEvents;

	private final Participations participations;

	private final AccountStatistics accountStatistics;

	private final CustomerProfiles customerProfiles;

	CustomerActivityReport(StudioSettings studioSettings, PassHolders passHolders, ScheduledEvents scheduledEvents,
			Participations participations, AccountStatistics accountStatistics, CustomerProfiles customerProfiles) {
		this.studioSettings = studioSettings;
		this.passHolders = passHolders;
		this.scheduledEvents = scheduledEvents;
		this.participations = participations;
		this.accountStatistics = accountStatistics;
		this.customerProfiles = customerProfiles;
	}

	/** The period {@code [from, to]} and the period of the same length right before it. */
	Comparison compare(LocalDate from, LocalDate to) {
		ZoneId zone = studioSettings.zone();
		long days = ChronoUnit.DAYS.between(from, to) + 1;
		return new Comparison(count(from, to, zone), count(from.minusDays(days), from.minusDays(1), zone));
	}

	private CustomerActivity count(LocalDate from, LocalDate to, ZoneId zone) {
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		Set<UUID> active = new HashSet<>(passHolders.findValidBetween(from, to));
		active.addAll(participations.findParticipants(scheduledEvents.findIdsStartingBetween(start, end)));
		long newCustomers = accountStatistics.countClientsActivated(start, end)
				+ customerProfiles.countCreatedWithoutAccount(start, end);
		return new CustomerActivity(from, to, active.size(), newCustomers);
	}

	record CustomerActivity(LocalDate from, LocalDate to, long activeCustomers, long newCustomers) {
	}

	record Comparison(CustomerActivity current, CustomerActivity previous) {
	}

}
