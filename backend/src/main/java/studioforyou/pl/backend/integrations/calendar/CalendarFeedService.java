package studioforyou.pl.backend.integrations.calendar;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.Participations.CalendarParticipation;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.identity.AccountRoles;
import studioforyou.pl.backend.integrations.calendar.ICalendarWriter.Entry;
import studioforyou.pl.backend.scheduling.CalendarEvents;
import studioforyou.pl.backend.scheduling.CalendarEvents.CalendarEvent;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Private one-way calendar feeds (#121): an account gets a secret link to an iCalendar document
 * with its schedule, which Google Calendar, Apple Calendar and other calendars subscribe to and
 * download again from time to time. Changes in the studio reach the calendar with its next
 * download; nothing is sent back.
 */
@Service
@EnableConfigurationProperties(CalendarFeedProperties.class)
class CalendarFeedService {

	/** How far back a feed reaches, so recent events stay in the calendar. */
	static final Duration PAST = Duration.ofDays(30);

	static final String FEED_PATH = "/api/v1/calendar-feeds/";

	static final String FEED_FILE = "/events.ics";

	private static final String UID_DOMAIN = "@studioforyou.pl";

	private final CalendarFeedStore store;

	private final CalendarFeedProperties properties;

	private final AccountRoles accountRoles;

	private final CustomerProfiles customerProfiles;

	private final StaffProfiles staffProfiles;

	private final Participations participations;

	private final CalendarEvents calendarEvents;

	CalendarFeedService(CalendarFeedStore store, CalendarFeedProperties properties, AccountRoles accountRoles,
			CustomerProfiles customerProfiles, StaffProfiles staffProfiles, Participations participations,
			CalendarEvents calendarEvents) {
		this.store = store;
		this.properties = properties;
		this.accountRoles = accountRoles;
		this.customerProfiles = customerProfiles;
		this.staffProfiles = staffProfiles;
		this.participations = participations;
		this.calendarEvents = calendarEvents;
	}

	/** When the account's current link was created, if it has one that works. */
	@Transactional(readOnly = true)
	Optional<Instant> findActive(UUID accountId) {
		return this.store.findActiveCreatedAt(accountId);
	}

	/**
	 * Creates a new link for the account. The previous link stops working, so calendars subscribed
	 * to it no longer get updates. The token is returned only here; the database keeps its hash.
	 */
	@Transactional
	IssuedFeed issue(UUID accountId) {
		String token = CalendarFeedTokens.generate();
		Instant createdAt = this.store.replace(accountId, CalendarFeedTokens.hash(token));
		String base = this.properties.baseUrl().replaceAll("/+$", "");
		return new IssuedFeed(base + FEED_PATH + token + FEED_FILE, createdAt);
	}

	/** The account's link stops working. Does nothing without one. */
	@Transactional
	void revoke(UUID accountId) {
		this.store.revoke(accountId);
	}

	/**
	 * The iCalendar document behind a link: the parts of the account's schedule that the role
	 * selects, or every part for its roles when {@code role} is {@code null}. An unknown or revoked
	 * token, or an account that is no longer active, gives {@code NOT_FOUND}.
	 */
	@Transactional(readOnly = true)
	String render(String token, @Nullable CalendarFeedRole role, Instant now) {
		UUID accountId = this.store.findAccountByActiveTokenHash(CalendarFeedTokens.hash(token))
			.filter((id) -> !this.accountRoles.findActive(Set.of(id)).isEmpty())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Calendar feed not found."));
		return ICalendarWriter.write(calendarName(role),
				entries(accountId, role, now, false).stream().map(ScheduleEntry::entry).toList(), now);
	}

	/**
	 * The account's schedule as calendar entries, the same as in its feed: the parts that the role
	 * selects, or every part for its roles when {@code role} is {@code null}. The app writes them to
	 * the phone's calendar when the user syncs it right away.
	 *
	 * @param labelHeldRoles prefix titles with the role whenever the account is both a client and a
	 * trainer, even for one part, so an event keeps its title whichever part the app syncs; otherwise
	 * only when both parts are in one calendar
	 */
	@Transactional(readOnly = true)
	List<ScheduleEntry> entries(UUID accountId, @Nullable CalendarFeedRole role, Instant now,
			boolean labelHeldRoles) {
		Set<String> held = this.accountRoles.rolesOf(Set.of(accountId)).getOrDefault(accountId, Set.of());
		Set<CalendarFeedRole> parts = EnumSet.noneOf(CalendarFeedRole.class);
		for (CalendarFeedRole part : (role == null) ? EnumSet.allOf(CalendarFeedRole.class) : EnumSet.of(role)) {
			if (held.contains(part.name())) {
				parts.add(part);
			}
		}
		// An account with both parts in one calendar sees which role each event concerns.
		boolean labelRoles = labelHeldRoles
				? held.contains(CalendarFeedRole.CLIENT.name()) && held.contains(CalendarFeedRole.TRAINER.name())
				: parts.size() > 1;
		Instant endingAfter = now.minus(PAST);
		List<ScheduleEntry> entries = new ArrayList<>();
		if (parts.contains(CalendarFeedRole.CLIENT)) {
			clientEntries(accountId, endingAfter, labelRoles)
				.forEach((entry) -> entries.add(new ScheduleEntry(CalendarFeedRole.CLIENT, entry)));
		}
		if (parts.contains(CalendarFeedRole.TRAINER)) {
			trainerEntries(accountId, endingAfter, labelRoles)
				.forEach((entry) -> entries.add(new ScheduleEntry(CalendarFeedRole.TRAINER, entry)));
		}
		entries.sort(Comparator.comparing((ScheduleEntry scheduled) -> scheduled.entry().startsAt())
			.thenComparing((scheduled) -> scheduled.entry().uid()));
		return entries;
	}

	private List<Entry> clientEntries(UUID accountId, Instant endingAfter, boolean labelRole) {
		Set<UUID> profileIds = this.customerProfiles.findProfileIdsActedForBy(accountId);
		List<CalendarParticipation> participations = this.participations.findForCalendar(profileIds, endingAfter);
		if (participations.isEmpty()) {
			return List.of();
		}
		Optional<UUID> ownProfileId = this.customerProfiles.findOwnProfile(accountId)
			.map(CustomerProfiles.ProfileSummary::id);
		Set<UUID> otherProfileIds = new HashSet<>(profileIds);
		ownProfileId.ifPresent(otherProfileIds::remove);
		Map<UUID, String> firstNames = this.customerProfiles.findAllById(otherProfileIds).stream()
			.collect(Collectors.toMap(ProfileDetails::id, ProfileDetails::firstName));
		Map<UUID, CalendarEvent> events = byId(this.calendarEvents
			.findAllById(participations.stream().map(CalendarParticipation::eventId).collect(Collectors.toSet())));
		List<Entry> entries = new ArrayList<>();
		for (CalendarParticipation participation : participations) {
			CalendarEvent event = events.get(participation.eventId());
			if (event == null) {
				continue;
			}
			// A guardian sees whose event it is, as in notifications: "Zosia: Pole dance".
			String participant = firstNames.get(participation.customerId());
			String summary = (labelRole ? "Klient · " : "") + (participant != null ? participant + ": " : "")
					+ name(event);
			entries.add(entry(event, participation.eventId() + "-" + participation.customerId() + UID_DOMAIN, summary,
					participation.cancelledWithEvent() || event.cancelled()));
		}
		return entries;
	}

	private List<Entry> trainerEntries(UUID accountId, Instant endingAfter, boolean labelRole) {
		return this.staffProfiles.findByAccount(accountId)
			.map((staff) -> this.calendarEvents.findAssignedTo(staff.id(), endingAfter).stream()
				.map((event) -> entry(event, event.id() + "-staff-" + staff.id() + UID_DOMAIN,
						(labelRole ? "Trener · " : "") + name(event), event.cancelled()))
				.toList())
			.orElse(List.of());
	}

	private static Entry entry(CalendarEvent event, String uid, String summary, boolean cancelled) {
		String location = event.rooms().isEmpty() ? null : String.join(", ", event.rooms());
		String description = event.leads().isEmpty() ? null : "Prowadzi: " + String.join(", ", event.leads());
		return new Entry(uid, event.startsAt(), event.endsAt(), summary, location, description, cancelled,
				event.version());
	}

	/** The event's title, its service type's name, or what kind of event it is. */
	private static String name(CalendarEvent event) {
		if (event.title() != null && !event.title().isBlank()) {
			return event.title();
		}
		if (event.serviceTypeName() != null && !event.serviceTypeName().isBlank()) {
			return event.serviceTypeName();
		}
		return switch (event.kind()) {
			case "PERSONAL_TRAINING" -> "Trening personalny";
			case "WORKSHOP" -> "Warsztaty";
			case "CONSULTATION" -> "Konsultacja";
			default -> "Zajęcia";
		};
	}

	private static String calendarName(@Nullable CalendarFeedRole role) {
		if (role == null) {
			return "Studio – mój grafik";
		}
		return switch (role) {
			case CLIENT -> "Studio – moje zajęcia";
			case TRAINER -> "Studio – prowadzone zajęcia";
		};
	}

	private static Map<UUID, CalendarEvent> byId(List<CalendarEvent> events) {
		return events.stream().collect(Collectors.toMap(CalendarEvent::id, Function.identity()));
	}

	/**
	 * A calendar entry with the part of the schedule it belongs to.
	 */
	record ScheduleEntry(CalendarFeedRole role, Entry entry) {
	}

	/**
	 * A newly created link.
	 *
	 * @param url the feed's https address
	 */
	record IssuedFeed(String url, Instant createdAt) {

		/** The same address with the {@code webcal} scheme, which opens the subscription dialog on iOS and macOS. */
		String webcalUrl() {
			return this.url.replaceFirst("^https?://", "webcal://");
		}

	}

}
