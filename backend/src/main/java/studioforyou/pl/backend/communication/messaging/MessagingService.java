package studioforyou.pl.backend.communication.messaging;

import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.Participations.ActiveReservation;
import studioforyou.pl.backend.communication.messaging.ConversationAudience.Role;
import studioforyou.pl.backend.communication.messaging.ConversationStore.AttachmentCursor;
import studioforyou.pl.backend.communication.messaging.ConversationStore.Conversation;
import studioforyou.pl.backend.communication.messaging.ConversationStore.ListedAttachment;
import studioforyou.pl.backend.communication.messaging.ConversationStore.Message;
import studioforyou.pl.backend.communication.messaging.ConversationStore.Participant;
import studioforyou.pl.backend.communication.messaging.ConversationStore.Summary;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.AttachmentPage;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.AttachmentResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ClientResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ConversationAttachmentResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ConversationResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ConversationSummaryResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.CreateGroupChatRequest;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.EventTarget;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.GroupChatTargets;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.GroupTarget;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.MessagePage;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.MessagePreview;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.MessageResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.Person;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.TrainerResponse;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.customer.StaffAssignments;
import studioforyou.pl.backend.customer.TrainerAccounts;
import studioforyou.pl.backend.files.FileType;
import studioforyou.pl.backend.files.StoredFiles;
import studioforyou.pl.backend.files.StoredFiles.Content;
import studioforyou.pl.backend.files.StoredFiles.StoredFile;
import studioforyou.pl.backend.files.StoredFiles.Upload;
import studioforyou.pl.backend.identity.AccountRoles;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.EventSummary;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.UserText;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;
import studioforyou.pl.backend.trainings.TrainingGroups;

/**
 * 1:1 conversations between clients and trainers, and the chats of fixed groups and classes. The
 * signed-in account always comes from the token; what it may do is worked out on every request.
 */
@Service
@Transactional
class MessagingService {

	/** How far ahead a trainer sees classes to start a chat for. */
	static final Duration EVENT_TARGET_HORIZON = Duration.ofDays(30);

	/** How far back and ahead the trainer's classes make their participants the trainer's clients. */
	static final Duration CLIENT_CLASS_WINDOW = Duration.ofDays(30);

	private static final int MAX_PAGE_SIZE = 100;

	/** What can be attached to a message: photos and documents. */
	static final Set<FileType> ATTACHMENT_TYPES = EnumSet.allOf(FileType.class);

	/** The module attachments are stored for. */
	private static final String FILE_OWNER = "communication";

	private static final Comparator<Person> BY_NAME;

	static {
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		BY_NAME = Comparator.comparing(Person::displayName, collator).thenComparing(Person::accountId);
	}

	private final ConversationStore store;

	private final ConversationAudience audience;

	private final ScheduledEvents events;

	private final TrainingGroups groups;

	private final Participations participations;

	private final CustomerProfiles customers;

	private final StaffAssignments assignments;

	private final StaffProfiles staff;

	private final TrainerAccounts trainerAccounts;

	private final StudioSettings settings;

	private final Notifications notifications;

	private final AccountRoles accountRoles;

	private final ApplicationEventPublisher publisher;

	private final StoredFiles files;

	MessagingService(ConversationStore store, ConversationAudience audience, ScheduledEvents events,
			TrainingGroups groups, Participations participations, CustomerProfiles customers,
			StaffAssignments assignments, StaffProfiles staff, TrainerAccounts trainerAccounts,
			StudioSettings settings, AccountRoles accountRoles, ApplicationEventPublisher publisher,
			Notifications notifications, StoredFiles files) {
		this.store = store;
		this.audience = audience;
		this.events = events;
		this.groups = groups;
		this.participations = participations;
		this.customers = customers;
		this.assignments = assignments;
		this.staff = staff;
		this.trainerAccounts = trainerAccounts;
		this.settings = settings;
		this.accountRoles = accountRoles;
		this.notifications = notifications;
		this.publisher = publisher;
		this.files = files;
	}

	List<ConversationSummaryResponse> list(UUID accountId) {
		audience.refresh(accountId);
		List<Summary> summaries = store.listFor(accountId);
		Map<UUID, UUID> counterparts = store.findCounterparts(accountId, summaries.stream()
			.filter((summary) -> summary.kind() == ConversationKind.DIRECT)
			.map(Summary::id)
			.toList());
		Set<UUID> accounts = new HashSet<>(counterparts.values());
		summaries.stream().map(Summary::lastSenderId).filter(Objects::nonNull).forEach(accounts::add);
		Map<UUID, String> names = names(accounts);
		Map<UUID, String> groupNames = groups.names(summaries.stream()
			.filter((summary) -> summary.kind() == ConversationKind.GROUP)
			.map(Summary::trainingGroupId)
			.toList());
		Map<UUID, EventSummary> classes = events.findGroupEvents(summaries.stream()
			.filter((summary) -> summary.kind() == ConversationKind.EVENT)
			.map(Summary::eventId)
			.toList()).stream().collect(Collectors.toMap(EventSummary::id, Function.identity()));
		Map<UUID, List<StoredFile>> lastAttachments = attachments(summaries.stream()
			.filter((summary) -> summary.lastMessageId() != null && !Boolean.TRUE.equals(summary.lastDeleted()))
			.map(Summary::lastMessageId)
			.toList());
		Instant now = audience.now();
		return summaries.stream().map((summary) -> {
			Conversation chat = summary.conversation();
			Person counterpart = (chat.kind() == ConversationKind.DIRECT) ? person(counterparts.get(chat.id()), names) : null;
			EventSummary event = (chat.eventId() != null) ? classes.get(chat.eventId()) : null;
			List<StoredFile> attached = lastAttachments.getOrDefault(summary.lastMessageId(), List.of());
			MessagePreview last = (summary.lastMessageId() == null) ? null
					: new MessagePreview(summary.lastMessageId(),
							accountId.equals(summary.lastSenderId()) ? null : name(summary.lastSenderId(), names),
							accountId.equals(summary.lastSenderId()), summary.lastBody(),
							Boolean.TRUE.equals(summary.lastDeleted()), summary.lastCreatedAt().toInstant(),
							attached.size(), imageCount(attached));
			return new ConversationSummaryResponse(chat.id(), chat.kind(),
					title(chat, counterpart,
							(chat.trainingGroupId() != null) ? groupNames.get(chat.trainingGroupId()) : null, event),
					counterpart,
					chat.trainingGroupId(), chat.eventId(), (event != null) ? event.startsAt() : null,
					chat.clientsCanReply(), summary.leftAt() != null, isClosed(chat, event, now), last,
					summary.unreadCount());
		}).toList();
	}

	long countUnread(UUID accountId) {
		audience.refresh(accountId);
		return store.countUnread(accountId);
	}

	ConversationResponse get(UUID conversationId, UUID accountId) {
		return response(open(conversationId, accountId), accountId);
	}

	MessagePage messages(UUID conversationId, UUID accountId, @Nullable UUID before, @Nullable UUID after,
			@Nullable Instant deletedSince, int limit) {
		if (before != null && after != null) {
			throw ApiException.invalidField("after", FieldErrorCode.INVALID_VALUE, "Use either before or after.");
		}
		Access access = open(conversationId, accountId);
		int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
		Message beforeMessage = (before != null) ? requireMessage(conversationId, before) : null;
		Message afterMessage = (after != null) ? requireMessage(conversationId, after) : null;
		List<Message> page = store.listMessages(conversationId, access.visibleUntil(), beforeMessage, afterMessage,
				size + 1);
		boolean hasMore = page.size() > size;
		if (hasMore) {
			page = (after != null) ? page.subList(0, size) : page.subList(1, page.size());
		}
		Map<UUID, String> names = names(page.stream().map(Message::senderId).collect(Collectors.toSet()));
		Map<UUID, List<StoredFile>> attachments = attachments(
				page.stream().filter((message) -> message.deletedAt() == null).map(Message::id).toList());
		List<UUID> deleted = (deletedSince != null)
				? store.findDeletedSince(conversationId, deletedSince, access.visibleUntil()) : List.of();
		return new MessagePage(page.stream()
			.map((message) -> message(message, access, accountId, names,
					attachments.getOrDefault(message.id(), List.of())))
			.toList(), hasMore, deleted, store.deletionCursor());
	}

	/**
	 * Sends a message with text, attachments or both. Each attachment is checked (size, type
	 * recognized from its content) and stored before the message is saved; if anything fails,
	 * nothing is sent and the stored files are removed.
	 *
	 * @param body the text; blank when the message has attachments only
	 */
	MessageResponse send(UUID conversationId, UUID accountId, @Nullable String body,
			List<NewAttachment> attachments) {
		Access access = open(conversationId, accountId);
		if (!access.canWrite()) {
			throw new ApiException(ErrorCode.CONVERSATION_READ_ONLY, "You cannot write in this conversation.");
		}
		String text = (body != null) ? UserText.trimToNull(body) : null;
		if (text != null && text.length() > MessagingDtos.MAX_MESSAGE_LENGTH) {
			throw ApiException.invalidField("body", FieldErrorCode.TOO_LONG,
					"A message can have at most " + MessagingDtos.MAX_MESSAGE_LENGTH + " characters.");
		}
		if (attachments.size() > MessagingDtos.MAX_ATTACHMENTS) {
			throw ApiException.invalidField("files", FieldErrorCode.TOO_LONG,
					"A message can have at most " + MessagingDtos.MAX_ATTACHMENTS + " attachments.");
		}
		if (text == null && attachments.isEmpty()) {
			throw ApiException.invalidField("body", FieldErrorCode.REQUIRED, "The message is empty.");
		}
		List<StoredFile> saved = new ArrayList<>();
		for (NewAttachment attachment : attachments) {
			saved.add(files.save(new Upload(FILE_OWNER, "chat/" + conversationId, accountId, attachment.name(),
					attachment.size(), attachment.content()), ATTACHMENT_TYPES, MessagingDtos.MAX_ATTACHMENT_SIZE));
		}
		Message message = store.insertMessage(conversationId, accountId, text);
		for (int i = 0; i < saved.size(); i++) {
			store.insertAttachment(message.id(), saved.get(i).id(), i, saved.get(i).isImage());
		}
		read(conversationId, accountId);
		publisher.publishEvent(new MessageSent(conversationId, message.id(), accountId));
		return message(message, access, accountId, names(List.of(accountId)), saved);
	}

	/**
	 * The content of a file attached to a message the account can see: not deleted, and from
	 * before it left the group or class.
	 */
	Content attachment(UUID conversationId, UUID messageId, UUID fileId, UUID accountId) {
		Access access = open(conversationId, accountId);
		Message message = store.findMessage(conversationId, messageId).orElseThrow(MessagingService::attachmentNotFound);
		boolean hidden = access.visibleUntil() != null
				&& message.createdAt().toInstant().isAfter(access.visibleUntil());
		if (message.deletedAt() != null || hidden
				|| !store.findAttachments(List.of(messageId)).getOrDefault(messageId, List.of()).contains(fileId)) {
			throw attachmentNotFound();
		}
		return files.open(fileId);
	}

	/**
	 * The photos or the other files of the messages the account can see, oldest first: the latest
	 * page, the page before an attachment of the conversation (older ones) or the page after one
	 * (newer ones). Deleted messages are left out, and so are the ones from after the account left
	 * the group or class.
	 */
	AttachmentPage attachments(UUID conversationId, UUID accountId, AttachmentKind kind, @Nullable UUID before,
			@Nullable UUID after, int limit) {
		if (before != null && after != null) {
			throw ApiException.invalidField("after", FieldErrorCode.INVALID_VALUE, "Use either before or after.");
		}
		Access access = open(conversationId, accountId);
		int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
		AttachmentCursor beforeCursor = (before != null) ? requireAttachmentCursor(conversationId, before) : null;
		AttachmentCursor afterCursor = (after != null) ? requireAttachmentCursor(conversationId, after) : null;
		List<ListedAttachment> page = store.listAttachments(conversationId, kind == AttachmentKind.PHOTO,
				access.visibleUntil(), beforeCursor, afterCursor, size + 1);
		boolean hasMore = page.size() > size;
		if (hasMore) {
			page = (after != null) ? page.subList(0, size) : page.subList(1, page.size());
		}
		Map<UUID, StoredFile> stored = files.findAll(page.stream().map(ListedAttachment::fileId).toList());
		Map<UUID, String> names = names(page.stream().map(ListedAttachment::senderId).collect(Collectors.toSet()));
		return new AttachmentPage(page.stream()
			.filter((attachment) -> stored.containsKey(attachment.fileId()))
			.map((attachment) -> new ConversationAttachmentResponse(attachment.messageId(), attachment.senderId(),
					name(attachment.senderId(), names), attachment.senderId().equals(accountId),
					attachment.createdAt().toInstant(), attachmentResponse(stored.get(attachment.fileId()))))
			.toList(), hasMore);
	}

	private AttachmentCursor requireAttachmentCursor(UUID conversationId, UUID fileId) {
		return store.findAttachmentCursor(conversationId, fileId).orElseThrow(MessagingService::attachmentNotFound);
	}

	/**
	 * Who gets a notification about a sent message, and what it shows: for each recipient, the
	 * messages of the conversation it has not read yet. The recipients are the other people who can
	 * read the conversation now: the counterpart of a 1:1 conversation, or the leaders and members of
	 * a group or class chat (for a child, its guardians). Each gets it for the role it has in the
	 * conversation. Someone who has already read everything (e.g. the message was deleted) is left
	 * out; empty when the conversation is gone.
	 */
	@Transactional(readOnly = true)
	Optional<NewMessage> newMessage(MessageSent sent) {
		Optional<Conversation> found = store.find(sent.conversationId());
		if (found.isEmpty()) {
			return Optional.empty();
		}
		Conversation chat = found.get();
		Map<UUID, NotificationRole> recipients = new HashMap<>();
		String chatTitle = null;
		if (chat.kind() == ConversationKind.DIRECT) {
			UUID other = store.findCounterparts(sent.senderId(), List.of(chat.id())).get(chat.id());
			if (other != null) {
				Map<UUID, Set<String>> roles = accountRoles.rolesOf(List.of(other, sent.senderId()));
				recipients.put(other, directRole(roles.getOrDefault(other, Set.of()),
						roles.getOrDefault(sent.senderId(), Set.of()).contains(NotificationRole.TRAINER.name())));
			}
		}
		else {
			audience.members(chat).forEach((account) -> recipients.put(account, NotificationRole.CLIENT));
			audience.leaders(chat).forEach((account) -> recipients.put(account, NotificationRole.TRAINER));
			String groupName = (chat.kind() == ConversationKind.GROUP)
					? groups.names(List.of(chat.trainingGroupId())).get(chat.trainingGroupId()) : null;
			chatTitle = title(chat, null, groupName, audience.event(chat).orElse(null));
		}
		recipients.remove(sent.senderId());
		Map<UUID, List<Message>> unread = new HashMap<>();
		Set<UUID> senders = new HashSet<>();
		recipients.keySet().forEach((account) -> {
			List<Message> messages = store.findUnread(chat.id(), account);
			if (!messages.isEmpty()) {
				unread.put(account, messages);
				messages.forEach((message) -> senders.add(message.senderId()));
			}
		});
		Map<UUID, String> names = names(senders);
		Map<UUID, List<StoredFile>> latestAttachments = attachments(
				unread.values().stream().map((messages) -> messages.get(messages.size() - 1).id()).distinct().toList());
		Map<UUID, UnreadMessages> result = new HashMap<>();
		unread.forEach((account, messages) -> {
			// Who wrote them, the latest writer first.
			LinkedHashSet<String> writers = new LinkedHashSet<>();
			for (int i = messages.size() - 1; i >= 0; i--) {
				writers.add(name(messages.get(i).senderId(), names));
			}
			Message latest = messages.get(messages.size() - 1);
			List<StoredFile> attached = latestAttachments.getOrDefault(latest.id(), List.of());
			result.put(account, new UnreadMessages(recipients.get(account), messages.get(0).id(), latest.id(),
					latest.createdAt().toInstant(), messages.size(), List.copyOf(writers),
					Objects.requireNonNullElse(latest.body(), ""), attached.size(), imageCount(attached)));
		});
		return Optional.of(new NewMessage(chat.id(), chat.kind() == ConversationKind.DIRECT ? null : chatTitle,
				result));
	}

	/**
	 * The role a 1:1 conversation concerns for the recipient. Such a conversation is between a client
	 * and a trainer, so an account with both roles gets it as a client when a trainer wrote, and as
	 * a trainer when a client wrote.
	 */
	static NotificationRole directRole(Set<String> recipientRoles, boolean senderIsTrainer) {
		boolean trainer = recipientRoles.contains(NotificationRole.TRAINER.name());
		boolean client = recipientRoles.contains(NotificationRole.CLIENT.name());
		if (trainer && client) {
			return senderIsTrainer ? NotificationRole.CLIENT : NotificationRole.TRAINER;
		}
		return trainer ? NotificationRole.TRAINER : NotificationRole.CLIENT;
	}

	void deleteMessage(UUID conversationId, UUID messageId, UUID accountId) {
		Access access = open(conversationId, accountId);
		Message message = requireMessage(conversationId, messageId);
		if (access.visibleUntil() != null && message.createdAt().toInstant().isAfter(access.visibleUntil())) {
			throw messageNotFound();
		}
		if (!canDelete(message, access, accountId)) {
			throw new ApiException(ErrorCode.ACCESS_DENIED, "You cannot delete this message.");
		}
		store.deleteMessage(messageId);
		// Deleting a message deletes its files too.
		files.delete(store.findAttachments(List.of(messageId)).getOrDefault(messageId, List.of()));
	}

	void markRead(UUID conversationId, UUID accountId) {
		open(conversationId, accountId);
		read(conversationId, accountId);
	}

	/** The account has read the conversation, so also the notifications about its messages. */
	private void read(UUID conversationId, UUID accountId) {
		store.markRead(conversationId, accountId);
		notifications.markRead(accountId, NotificationType.NEW_MESSAGE, MessageNotifications.CONVERSATION_ID,
				conversationId.toString());
	}

	ConversationResponse update(UUID conversationId, UUID accountId, boolean clientsCanReply) {
		Access access = open(conversationId, accountId);
		if (!access.isLeader()) {
			throw new ApiException(ErrorCode.ACCESS_DENIED, "Only the staff running the group or class can do this.");
		}
		store.setClientsCanReply(conversationId, clientsCanReply);
		return get(conversationId, accountId);
	}

	void hide(UUID conversationId, UUID accountId) {
		Access access = open(conversationId, accountId);
		if (!access.canHide()) {
			throw new ApiException(ErrorCode.CONVERSATION_STILL_ACTIVE,
					"Only a conversation you no longer belong to can be removed.");
		}
		store.hide(conversationId, accountId);
	}

	/**
	 * Opens the 1:1 conversation with one of the signed-in trainer's clients, creating it if needed:
	 * a participant assigned to the trainer, in a group the trainer runs or booked on their class.
	 */
	Started startWithTrainee(UUID trainerAccountId, UUID customerId) {
		UUID staffId = requireStaffId(trainerAccountId);
		if (!assignments.isAssigned(customerId, staffId) && !classLinks(staffId).containsKey(customerId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "The participant is not your client.");
		}
		UUID clientAccount = customers.findContactAccount(customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.RECIPIENT_WITHOUT_ACCOUNT,
					"The participant has no account to write to."));
		return startDirect(trainerAccountId, clientAccount);
	}

	/** Opens the signed-in client's 1:1 conversation with an active trainer, creating it if needed. */
	Started startWithTrainer(UUID clientAccountId, UUID staffId) {
		StaffMember member = staff.findAllById(List.of(staffId))
			.stream()
			.filter(StaffMember::active)
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.STAFF_UNAVAILABLE,
					"The staff member does not exist or is not active."));
		UUID trainerAccount = staff.findAccountIds(List.of(member.id())).get(member.id());
		if (trainerAccount == null || trainerAccounts.findTrainers(List.of(trainerAccount)).isEmpty()) {
			throw new ApiException(ErrorCode.STAFF_NOT_TRAINER, "You can only write to a trainer.");
		}
		if (trainerAccount.equals(clientAccountId)) {
			throw ApiException.invalidField("staffId", FieldErrorCode.INVALID_VALUE, "You cannot write to yourself.");
		}
		return startDirect(clientAccountId, trainerAccount);
	}

	/** Active trainers the signed-in client can write to, sorted by name, without the client themselves. */
	@Transactional(readOnly = true)
	List<TrainerResponse> trainers(UUID clientAccountId) {
		List<StaffMember> active = staff.findAll(true);
		Map<UUID, UUID> accounts = staff.findAccountIds(active.stream().map(StaffMember::id).toList());
		Set<UUID> trainers = trainerAccounts.findTrainers(accounts.values());
		return active.stream()
			.filter((member) -> {
				UUID account = accounts.get(member.id());
				return account != null && trainers.contains(account) && !account.equals(clientAccountId);
			})
			.map((member) -> new TrainerResponse(member.id(), member.displayName(),
					store.findDirect(clientAccountId, accounts.get(member.id())).orElse(null)))
			.toList();
	}

	/**
	 * The signed-in trainer's clients who can be written to, sorted by name: participants assigned to
	 * the trainer, in the fixed groups the trainer runs, or booked on the trainer's classes from the
	 * last and next 30 days. Participants without an account to write to are left out.
	 */
	@Transactional(readOnly = true)
	List<ClientResponse> clients(UUID trainerAccountId) {
		UUID staffId = requireStaffId(trainerAccountId);
		Set<UUID> trainees = assignments.findAssignedCustomers(staffId);
		Map<UUID, Set<String>> links = classLinks(staffId);
		Set<UUID> ids = new HashSet<>(trainees);
		ids.addAll(links.keySet());
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		return customers.findAllById(ids)
			.stream()
			.filter(ProfileDetails::active)
			.sorted(Comparator.comparing(ProfileDetails::lastName, collator)
				.thenComparing(ProfileDetails::firstName, collator)
				.thenComparing(ProfileDetails::id))
			.flatMap((profile) -> customers.findContactAccount(profile.id())
				.filter((account) -> !account.equals(trainerAccountId))
				.map((account) -> new ClientResponse(profile.id(), profile.displayName(),
						profile.kind() == CustomerKind.CHILD, trainees.contains(profile.id()),
						List.copyOf(links.getOrDefault(profile.id(), Set.of())),
						store.findDirect(trainerAccountId, account).orElse(null)))
				.stream())
			.toList();
	}

	/**
	 * Participants of the fixed groups the trainer runs and of the trainer's classes from the last
	 * and next 30 days, each with the sorted names of those groups and classes.
	 */
	private Map<UUID, Set<String>> classLinks(UUID staffId) {
		Instant now = audience.now();
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		Set<UUID> ledGroups = events.findGroupsLedBy(staffId, now);
		List<EventSummary> classes = events.findGroupEventsAssignedTo(staffId, now.minus(CLIENT_CLASS_WINDOW),
				now.plus(CLIENT_CLASS_WINDOW));
		Set<UUID> groupIds = new HashSet<>(ledGroups);
		classes.stream().map(EventSummary::trainingGroupId).filter(Objects::nonNull).forEach(groupIds::add);
		Map<UUID, String> groupNames = groups.names(groupIds);
		Map<UUID, Set<String>> links = new HashMap<>();
		for (UUID groupId : ledGroups) {
			for (UUID customerId : groups.findMembers(groupId, settings.today())) {
				links.computeIfAbsent(customerId, (id) -> new TreeSet<>(collator))
					.add(groupNames.getOrDefault(groupId, ""));
			}
		}
		Map<UUID, EventSummary> byId = classes.stream()
			.collect(Collectors.toMap(EventSummary::id, Function.identity()));
		for (ActiveReservation reservation : participations.findActiveReservations(byId.keySet())) {
			EventSummary event = byId.get(reservation.eventId());
			String name = (event.trainingGroupId() != null)
					? groupNames.getOrDefault(event.trainingGroupId(), event.title()) : event.title();
			links.computeIfAbsent(reservation.customerId(), (id) -> new TreeSet<>(collator)).add(name);
		}
		return links;
	}

	/** Fixed groups and upcoming classes the signed-in trainer runs, with their chats if they have them. */
	@Transactional(readOnly = true)
	GroupChatTargets groupChatTargets(UUID trainerAccountId) {
		UUID staffId = requireStaffId(trainerAccountId);
		Instant now = audience.now();
		Set<UUID> groupIds = events.findGroupsLedBy(staffId, now);
		List<EventSummary> classes = events.findGroupEventsAssignedTo(staffId, now, now.plus(EVENT_TARGET_HORIZON));
		Map<UUID, UUID> groupChats = new HashMap<>();
		Map<UUID, UUID> eventChats = new HashMap<>();
		for (Conversation chat : store.findByTargets(groupIds, classes.stream().map(EventSummary::id).toList())) {
			if (chat.kind() == ConversationKind.GROUP) {
				groupChats.put(chat.trainingGroupId(), chat.id());
			}
			else {
				eventChats.put(chat.eventId(), chat.id());
			}
		}
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		List<GroupTarget> groupTargets = groups.names(groupIds)
			.entrySet()
			.stream()
			.map((group) -> new GroupTarget(group.getKey(), group.getValue(),
					audience.members(groupChat(group.getKey(), trainerAccountId)).size(),
					groupChats.get(group.getKey())))
			.sorted(Comparator.comparing(GroupTarget::name, collator))
			.toList();
		List<EventTarget> eventTargets = classes.stream()
			.map((event) -> new EventTarget(event.id(), event.title(), event.startsAt(),
					participations.findParticipants(List.of(event.id())).size(), eventChats.get(event.id())))
			.toList();
		return new GroupChatTargets(groupTargets, eventTargets);
	}

	/** Creates the chat of a fixed group or class the signed-in trainer runs. */
	ConversationResponse createGroupChat(UUID trainerAccountId, CreateGroupChatRequest request) {
		if ((request.trainingGroupId() == null) == (request.eventId() == null)) {
			throw ApiException.invalidField("trainingGroupId", FieldErrorCode.INVALID_VALUE,
					"Choose either a group or a class.");
		}
		UUID staffId = requireStaffId(trainerAccountId);
		Instant now = audience.now();
		String title = UserText.trimToNull(request.title());
		UUID id;
		if (request.trainingGroupId() != null) {
			UUID groupId = request.trainingGroupId();
			if (!events.findGroupsLedBy(staffId, now).contains(groupId)) {
				throw new ApiException(ErrorCode.NOT_FOUND, "You do not run this group.");
			}
			if (!store.findByTargets(List.of(groupId), List.of()).isEmpty()) {
				throw ConversationStore.alreadyExists();
			}
			if (title == null) {
				title = groups.names(List.of(groupId)).get(groupId);
			}
			id = store.createGroupChat(ConversationKind.GROUP, groupId, null, title, request.clientsCanReply(),
					trainerAccountId);
		}
		else {
			UUID eventId = request.eventId();
			EventSummary event = events.findGroupEvents(List.of(eventId))
				.stream()
				.filter((candidate) -> events.findStaff(eventId).contains(staffId))
				.findFirst()
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "You do not run this class."));
			if (event.cancelled() || !event.endsAt().isAfter(now)) {
				throw new ApiException(ErrorCode.CONVERSATION_READ_ONLY, "The class has ended or was cancelled.");
			}
			if (!store.findByTargets(List.of(), List.of(eventId)).isEmpty()) {
				throw ConversationStore.alreadyExists();
			}
			if (title == null) {
				title = event.title();
			}
			id = store.createGroupChat(ConversationKind.EVENT, null, eventId, title, request.clientsCanReply(),
					trainerAccountId);
		}
		store.join(id, trainerAccountId);
		return get(id, trainerAccountId);
	}

	private Started startDirect(UUID accountId, UUID otherAccountId) {
		store.lockPair(accountId, otherAccountId);
		Optional<UUID> existing = store.findDirect(accountId, otherAccountId);
		UUID id = existing.orElseGet(() -> store.createDirect(accountId, otherAccountId));
		return new Started(get(id, accountId), existing.isEmpty());
	}

	/**
	 * What the account may do in the conversation now. A group or class chat follows the schedule
	 * and bookings, so the account joins it, comes back to it or leaves it here. A conversation the
	 * account has never been in looks the same as one that does not exist.
	 */
	private Access open(UUID conversationId, UUID accountId) {
		Conversation chat = store.find(conversationId).orElseThrow(MessagingService::conversationNotFound);
		if (chat.kind() == ConversationKind.DIRECT) {
			Participant participant = store.findParticipant(conversationId, accountId)
				.orElseThrow(MessagingService::conversationNotFound);
			return new Access(chat, Role.MEMBER, participant, null, false);
		}
		Role role = audience.roleOf(chat, accountId);
		if (role == Role.NONE) {
			store.leave(conversationId, accountId);
		}
		else {
			store.join(conversationId, accountId);
		}
		Participant participant = store.findParticipant(conversationId, accountId)
			.orElseThrow(MessagingService::conversationNotFound);
		EventSummary event = audience.event(chat).orElse(null);
		return new Access(chat, role, participant, event, isClosed(chat, event, audience.now()));
	}

	private ConversationResponse response(Access access, UUID accountId) {
		Conversation chat = access.chat();
		List<Person> leaders;
		List<Person> members;
		Person counterpart = null;
		String groupName = null;
		if (chat.kind() == ConversationKind.DIRECT) {
			UUID other = store.findCounterparts(accountId, List.of(chat.id())).get(chat.id());
			Map<UUID, String> names = names((other != null) ? List.of(accountId, other) : List.of(accountId));
			counterpart = person(other, names);
			leaders = List.of();
			members = names.keySet().stream().map((id) -> person(id, names)).sorted(BY_NAME).toList();
		}
		else {
			Set<UUID> leaderIds = audience.leaders(chat);
			Set<UUID> memberIds = audience.members(chat);
			Set<UUID> everyone = new HashSet<>(leaderIds);
			everyone.addAll(memberIds);
			Map<UUID, String> names = names(everyone);
			leaders = leaderIds.stream().map((id) -> person(id, names)).sorted(BY_NAME).toList();
			members = memberIds.stream().map((id) -> person(id, names)).sorted(BY_NAME).toList();
			if (chat.kind() == ConversationKind.GROUP) {
				groupName = groups.names(List.of(chat.trainingGroupId())).get(chat.trainingGroupId());
			}
		}
		EventSummary event = access.event();
		return new ConversationResponse(chat.id(), chat.kind(), title(chat, counterpart, groupName, event),
				counterpart, chat.trainingGroupId(), chat.eventId(), (event != null) ? event.startsAt() : null,
				chat.clientsCanReply(), access.hasLeft(), access.isClosed(), access.isLeader(), access.canWrite(),
				access.canHide(), access.visibleUntil(), leaders, members);
	}

	private MessageResponse message(Message message, Access access, UUID accountId, Map<UUID, String> names,
			List<StoredFile> attachments) {
		List<AttachmentResponse> attached = (message.deletedAt() != null) ? List.of()
				: attachments.stream().map(MessagingService::attachmentResponse).toList();
		return new MessageResponse(message.id(), message.senderId(), name(message.senderId(), names),
				message.senderId().equals(accountId), message.body(), message.deletedAt() != null,
				message.createdAt().toInstant(), canDelete(message, access, accountId), attached);
	}

	private static AttachmentResponse attachmentResponse(StoredFile file) {
		return new AttachmentResponse(file.id(), file.name(), file.mimeType(), file.sizeBytes(), file.isImage(),
				file.imageWidth(), file.imageHeight());
	}

	/** The files attached to each of the given messages, in their order; deleted files are left out. */
	private Map<UUID, List<StoredFile>> attachments(Collection<UUID> messageIds) {
		Map<UUID, List<UUID>> attached = store.findAttachments(messageIds);
		Map<UUID, StoredFile> stored = files.findAll(attached.values().stream().flatMap(List::stream).toList());
		Map<UUID, List<StoredFile>> result = new HashMap<>();
		attached.forEach((messageId, fileIds) -> {
			List<StoredFile> found = fileIds.stream().map(stored::get).filter(Objects::nonNull).toList();
			if (!found.isEmpty()) {
				result.put(messageId, found);
			}
		});
		return result;
	}

	private static int imageCount(List<StoredFile> files) {
		return (int) files.stream().filter(StoredFile::isImage).count();
	}

	private static boolean canDelete(Message message, Access access, UUID accountId) {
		if (message.deletedAt() != null || access.hasLeft()) {
			return false;
		}
		return message.senderId().equals(accountId) || access.isLeader();
	}

	private Message requireMessage(UUID conversationId, UUID messageId) {
		return store.findMessage(conversationId, messageId).orElseThrow(MessagingService::messageNotFound);
	}

	private UUID requireStaffId(UUID accountId) {
		return staff.findByAccount(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "The account has no staff profile."))
			.id();
	}

	/** A group chat that does not exist yet, to count the group's members. */
	private static Conversation groupChat(UUID groupId, UUID creatorId) {
		return new Conversation(new UUID(0, 0), ConversationKind.GROUP, groupId, null, null, creatorId,
				OffsetDateTime.now(), true);
	}

	/** Names of accounts: the staff profile's name, otherwise the own client profile's name. */
	private Map<UUID, String> names(Collection<UUID> accountIds) {
		Map<UUID, String> names = new HashMap<>(customers.displayNamesByAccount(accountIds));
		names.putAll(staff.displayNamesByAccount(accountIds));
		return names;
	}

	private static String name(UUID accountId, Map<UUID, String> names) {
		return names.getOrDefault(accountId, "");
	}

	private static Person person(@Nullable UUID accountId, Map<UUID, String> names) {
		return (accountId == null) ? null : new Person(accountId, name(accountId, names));
	}

	private static String title(Conversation chat, @Nullable Person counterpart, @Nullable String groupName,
			@Nullable EventSummary event) {
		if (chat.kind() == ConversationKind.DIRECT) {
			return (counterpart != null) ? counterpart.displayName() : "";
		}
		if (chat.title() != null && !chat.title().isBlank()) {
			return chat.title();
		}
		if (groupName != null) {
			return groupName;
		}
		return (event != null) ? event.title() : "";
	}

	private static boolean isClosed(Conversation chat, @Nullable EventSummary event, Instant now) {
		return chat.kind() == ConversationKind.EVENT
				&& (event == null || event.cancelled() || !event.endsAt().isAfter(now));
	}

	private static ApiException conversationNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Conversation not found.");
	}

	private static ApiException messageNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Message not found.");
	}

	private static ApiException attachmentNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Attachment not found.");
	}

	/**
	 * What a notification about new messages shows to each recipient (by account).
	 *
	 * @param chatTitle the name of a group or class chat, {@code null} for a 1:1 conversation
	 */
	record NewMessage(UUID conversationId, @Nullable String chatTitle, Map<UUID, UnreadMessages> recipients) {
	}

	/**
	 * The messages a recipient has not read yet in a conversation.
	 *
	 * @param role the role the conversation concerns for the recipient
	 * @param firstId the oldest unread message; the same until the recipient reads the conversation
	 * @param latestId the newest unread message
	 * @param latestAt when the newest one was written
	 * @param count how many there are
	 * @param senders who wrote them, the latest writer first
	 * @param latestBody the text of the newest one; empty when it has attachments only
	 * @param latestAttachmentCount how many files are attached to the newest one
	 * @param latestImageCount how many of them are photos
	 */
	record UnreadMessages(NotificationRole role, UUID firstId, UUID latestId, Instant latestAt, int count,
			List<String> senders, String latestBody, int latestAttachmentCount, int latestImageCount) {
	}

	/**
	 * A file sent with a message.
	 *
	 * @param name the name the client gave it, if any
	 * @param size its size in bytes
	 * @param content its content, which can be read more than once
	 */
	record NewAttachment(@Nullable String name, long size, InputStreamSource content) {
	}

	/** A conversation that was opened, and whether it was created by this request. */
	record Started(ConversationResponse conversation, boolean created) {
	}

	private record Access(Conversation chat, Role role, Participant participant, @Nullable EventSummary event,
			boolean isClosed) {

		boolean isLeader() {
			return role == Role.LEADER;
		}

		boolean hasLeft() {
			return chat.kind() != ConversationKind.DIRECT && role == Role.NONE;
		}

		boolean canWrite() {
			if (chat.kind() == ConversationKind.DIRECT) {
				return true;
			}
			return !isClosed && (role == Role.LEADER || (role == Role.MEMBER && chat.clientsCanReply()));
		}

		boolean canHide() {
			return hasLeft();
		}

		@Nullable Instant visibleUntil() {
			return (hasLeft() && participant.leftAt() != null) ? participant.leftAt().toInstant() : null;
		}

	}

}
