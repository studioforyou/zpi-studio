package studioforyou.pl.backend.communication.messaging;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.communication.messaging.ConversationStore.Conversation;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.EventSummary;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffSummary;
import studioforyou.pl.backend.trainings.TrainingGroups;

/**
 * Who belongs to a group or class chat right now. Membership is not stored by hand: it follows
 * the schedule, bookings and fixed groups.
 * <ul>
 * <li>Leaders write whatever the chat's settings: the staff running or assisting the class, or
 * the group's classes that have not ended yet, and the trainer who created the chat.</li>
 * <li>Members are the clients acting for a participant: booked on the class, or belonging to the
 * group or booked on one of its classes that have not ended yet.</li>
 * </ul>
 */
@Component
class ConversationAudience {

	private final Clock clock = Clock.systemUTC();

	private final ConversationStore store;

	private final ScheduledEvents events;

	private final Participations participations;

	private final TrainingGroups groups;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final StudioSettings settings;

	ConversationAudience(ConversationStore store, ScheduledEvents events, Participations participations,
			TrainingGroups groups, CustomerProfiles customers, StaffProfiles staff, StudioSettings settings) {
		this.store = store;
		this.events = events;
		this.participations = participations;
		this.groups = groups;
		this.customers = customers;
		this.staff = staff;
		this.settings = settings;
	}

	Instant now() {
		return clock.instant();
	}

	/**
	 * Adds the account to the group and class chats it belongs to now and marks it as having left
	 * the others, so its list of conversations is up to date.
	 */
	void refresh(UUID accountId) {
		Instant now = now();
		Set<UUID> groupIds = new HashSet<>();
		Set<UUID> eventIds = new HashSet<>();
		Optional<StaffSummary> member = staff.findByAccount(accountId);
		if (member.isPresent()) {
			eventIds.addAll(events.findGroupEventIdsAssignedTo(member.get().id()));
			groupIds.addAll(events.findGroupsLedBy(member.get().id(), now));
		}
		Set<UUID> profiles = customers.findProfileIdsActedForBy(accountId);
		if (!profiles.isEmpty()) {
			Set<UUID> booked = participations.findActiveEventIds(profiles);
			eventIds.addAll(booked);
			groupIds.addAll(groups.findGroupsOf(profiles, settings.today()));
			groupIds.addAll(events.findGroupsOfUpcoming(booked, now));
		}
		List<Conversation> chats = store.findGroupChats(groupIds, eventIds, accountId);
		for (Conversation chat : chats) {
			store.join(chat.id(), accountId);
		}
		store.leaveGroupChatsExcept(accountId, chats.stream().map(Conversation::id).toList());
	}

	/** The account's role in a group or class chat now, ignoring whether it was ever a participant. */
	Role roleOf(Conversation chat, UUID accountId) {
		if (leaders(chat).contains(accountId)) {
			return Role.LEADER;
		}
		Set<UUID> profiles = new HashSet<>(customers.findProfileIdsActedForBy(accountId));
		profiles.retainAll(memberProfiles(chat));
		return profiles.isEmpty() ? Role.NONE : Role.MEMBER;
	}

	/** Accounts of the chat's leaders. */
	Set<UUID> leaders(Conversation chat) {
		Set<UUID> staffIds = switch (chat.kind()) {
			case EVENT -> events.findStaff(chat.eventId());
			case GROUP -> events.findGroupLeaders(chat.trainingGroupId(), now());
			case DIRECT -> Set.of();
		};
		Set<UUID> accounts = new HashSet<>(staff.findAccountIds(staffIds).values());
		if (chat.kind() != ConversationKind.DIRECT) {
			accounts.add(chat.createdBy());
		}
		return accounts;
	}

	/** Accounts of the chat's members, without its leaders. */
	Set<UUID> members(Conversation chat) {
		Set<UUID> accounts = new HashSet<>(customers.findAccountsActingFor(memberProfiles(chat)));
		accounts.removeAll(leaders(chat));
		return accounts;
	}

	/** The class of an event chat; empty for other chats or a class that no longer exists. */
	Optional<EventSummary> event(Conversation chat) {
		if (chat.eventId() == null) {
			return Optional.empty();
		}
		return events.findGroupEvents(List.of(chat.eventId())).stream().findFirst();
	}

	private Set<UUID> memberProfiles(Conversation chat) {
		return switch (chat.kind()) {
			case EVENT -> participations.findParticipants(List.of(chat.eventId()));
			case GROUP -> {
				Set<UUID> profiles = new HashSet<>(groups.findMembers(chat.trainingGroupId(), settings.today()));
				profiles.addAll(participations
					.findParticipants(events.findUpcomingEventIdsOfGroup(chat.trainingGroupId(), now())));
				yield profiles;
			}
			case DIRECT -> Set.of();
		};
	}

	enum Role {

		LEADER, MEMBER, NONE

	}

}
