package studioforyou.pl.backend.reporting.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.Participations.LastVisit;
import studioforyou.pl.backend.communication.PersonalAnnouncements;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ContactDetails;
import studioforyou.pl.backend.documents.DocumentKind;
import studioforyou.pl.backend.documents.MarketingConsents;
import studioforyou.pl.backend.identity.AccountStatistics;
import studioforyou.pl.backend.membership.PassHolders;
import studioforyou.pl.backend.membership.PassHolders.CurrentPass;

/**
 * Customers who stopped coming, as of now. A participant (an adult or a child) has stopped coming
 * when they visited the studio at least once, their last visit was at least the given number of
 * days ago (studio dates) and they have no reservation for an upcoming event. Archived profiles
 * and adults whose account is not active (e.g. suspended) are left out.
 */
@Service
class LapsedCustomersReport {

	/** How far back from the last visit the visits that show how regular the customer was are counted. */
	static final int RECENT_VISITS_DAYS = 90;

	private final StudioSettings studioSettings;

	private final Participations participations;

	private final CustomerProfiles customerProfiles;

	private final AccountStatistics accountStatistics;

	private final PassHolders passHolders;

	private final MarketingConsents marketingConsents;

	private final PersonalAnnouncements personalAnnouncements;

	LapsedCustomersReport(StudioSettings studioSettings, Participations participations,
			CustomerProfiles customerProfiles, AccountStatistics accountStatistics, PassHolders passHolders,
			MarketingConsents marketingConsents, PersonalAnnouncements personalAnnouncements) {
		this.studioSettings = studioSettings;
		this.participations = participations;
		this.customerProfiles = customerProfiles;
		this.accountStatistics = accountStatistics;
		this.passHolders = passHolders;
		this.marketingConsents = marketingConsents;
		this.personalAnnouncements = personalAnnouncements;
	}

	Result find(int inactiveDays, Instant now) {
		ZoneId zone = studioSettings.zone();
		LocalDate today = LocalDate.ofInstant(now, zone);
		Map<UUID, LastVisit> visits = participations.findLastVisits(now, RECENT_VISITS_DAYS);
		// Only active profiles come back, and adults whose account is not active are dropped.
		List<ContactDetails> everVisited = customerProfiles.activeContactDetails(visits.keySet());
		Set<UUID> blocked = accountStatistics.findNotActive(everVisited.stream()
			.filter((contact) -> contact.kind() == CustomerKind.ADULT)
			.map(ContactDetails::contactAccountId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		List<ContactDetails> customers = everVisited.stream()
			.filter((contact) -> contact.kind() != CustomerKind.ADULT || !blocked.contains(contact.contactAccountId()))
			.toList();
		Set<UUID> upcoming = participations.findWithUpcomingReservations(now);
		List<ContactDetails> lapsed = customers.stream()
			.filter((contact) -> !upcoming.contains(contact.id()))
			.filter((contact) -> daysSince(visits.get(contact.id()), today, zone) >= inactiveDays)
			.toList();

		Map<UUID, CurrentPass> passes = passHolders
			.findCurrentPasses(lapsed.stream().map(ContactDetails::id).toList(), today);
		Set<UUID> consenting = marketingConsents.consenting(
				lapsed.stream().map(ContactDetails::contactProfileId).collect(Collectors.toSet()),
				DocumentKind.MARKETING_PUSH);
		Set<UUID> contactAccounts = new HashSet<>();
		lapsed.stream().map(ContactDetails::contactAccountId).filter(Objects::nonNull).forEach(contactAccounts::add);
		Map<UUID, Instant> announcements = personalAnnouncements.lastSentTo(contactAccounts);

		List<LapsedCustomer> rows = lapsed.stream().map((contact) -> {
			LastVisit visit = visits.get(contact.id());
			UUID account = contact.contactAccountId();
			return new LapsedCustomer(contact, LocalDate.ofInstant(visit.lastAt(), zone),
					daysSince(visit, today, zone), visit.recentVisits(), passes.get(contact.id()),
					consenting.contains(contact.contactProfileId()),
					(account != null && announcements.containsKey(account))
							? LocalDate.ofInstant(announcements.get(account), zone) : null);
		})
			.sorted(Comparator.comparing(LapsedCustomer::lastVisit)
				.thenComparing((row) -> row.contact().lastName())
				.thenComparing((row) -> row.contact().firstName())
				.thenComparing((row) -> row.contact().id()))
			.toList();
		return new Result(today, inactiveDays, customers.size(), rows);
	}

	private static long daysSince(LastVisit visit, LocalDate today, ZoneId zone) {
		return ChronoUnit.DAYS.between(LocalDate.ofInstant(visit.lastAt(), zone), today);
	}

	/**
	 * @param everVisited customers counted the same way who visited the studio at least once
	 */
	record Result(LocalDate asOf, int inactiveDays, long everVisited, List<LapsedCustomer> customers) {
	}

	/**
	 * @param lastVisit studio date of the last visit
	 * @param recentVisits visits in the {@value #RECENT_VISITS_DAYS} days up to and including the last one
	 * @param pass the pass that tells whether they can still come; {@code null} if they never held one
	 * @param marketingConsent whether the person contacted accepted marketing notifications in the app
	 * @param lastAnnouncement studio date the person contacted last got an announcement addressed to them alone
	 */
	record LapsedCustomer(ContactDetails contact, LocalDate lastVisit, long daysSinceLastVisit, int recentVisits,
			@Nullable CurrentPass pass, boolean marketingConsent, @Nullable LocalDate lastAnnouncement) {
	}

}
