package studioforyou.pl.backend.communication.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Requests and responses of the conversations API. */
final class MessagingDtos {

	/** Longest message text, in characters. */
	static final int MAX_MESSAGE_LENGTH = 2000;

	/** Longest title of a group or class chat, as in {@code conversation.title}. */
	static final int MAX_TITLE_LENGTH = 120;

	private MessagingDtos() {
	}

	/**
	 * A conversation in the signed-in user's list.
	 *
	 * @param title the other person's name in a 1:1 conversation, otherwise the chat's title
	 * @param counterpart the other person of a 1:1 conversation; {@code null} for other kinds
	 * @param eventStartsAt start of the class of a class chat; {@code null} for other kinds
	 * @param hasLeft whether the user no longer belongs to the group or class; they see only the
	 * messages from before they left and cannot write
	 * @param isClosed whether the class of a class chat has ended or was cancelled; nobody can write
	 * @param lastMessage the latest message the user can see, or {@code null}
	 */
	@Schema(name = "ConversationSummary")
	record ConversationSummaryResponse(UUID id, ConversationKind kind,
			@Schema(example = "Pole dance początkujący wt/czw") String title, @Nullable Person counterpart,
			@Nullable UUID trainingGroupId, @Nullable UUID eventId, @Nullable Instant eventStartsAt,
			boolean clientsCanReply, boolean hasLeft, boolean isClosed, @Nullable MessagePreview lastMessage,
			long unreadCount) {
	}

	/**
	 * A conversation opened by the signed-in user, with what they may do in it.
	 *
	 * @param isLeader whether the user runs the group or class (or created the chat): they always
	 * write, delete other people's messages and change the chat's settings
	 * @param canWrite whether the user may send messages now
	 * @param canHide whether the user may remove the conversation from their list (only after they
	 * left the group or class)
	 * @param visibleUntil the user sees only messages up to this moment; {@code null} when they see all
	 * @param leaders the staff running the group or class; empty for 1:1 conversations
	 * @param members the clients in the chat, sorted by name; for 1:1 conversations both people
	 */
	@Schema(name = "Conversation")
	record ConversationResponse(UUID id, ConversationKind kind, String title, @Nullable Person counterpart,
			@Nullable UUID trainingGroupId, @Nullable UUID eventId, @Nullable Instant eventStartsAt,
			boolean clientsCanReply, boolean hasLeft, boolean isClosed, boolean isLeader, boolean canWrite,
			boolean canHide, @Nullable Instant visibleUntil, List<Person> leaders, List<Person> members) {
	}

	/** A person in a conversation, by their account. */
	@Schema(name = "ConversationPerson")
	record Person(UUID accountId, @Schema(example = "Anna Wiśniewska") String displayName) {
	}

	/**
	 * @param senderName the author's name; {@code null} for the user's own message
	 * @param body the text; {@code null} for a deleted message
	 */
	@Schema(name = "MessagePreview")
	record MessagePreview(UUID id, @Nullable String senderName, boolean isMine, @Nullable String body,
			boolean isDeleted, Instant createdAt) {
	}

	/**
	 * @param body the text; {@code null} for a deleted message, which keeps its place
	 * @param canDelete whether the signed-in user may delete it: their own message, or anyone's for
	 * the staff running the group or class
	 */
	@Schema(name = "Message")
	record MessageResponse(UUID id, UUID senderAccountId, @Schema(example = "Ola Lewandowska") String senderName,
			boolean isMine, @Nullable String body, boolean isDeleted, Instant createdAt, boolean canDelete) {
	}

	/**
	 * Messages oldest first.
	 *
	 * @param hasMore whether there are older messages than the first one ({@code before} or the latest
	 * page) or newer ones than the last one ({@code after})
	 * @param deletedIds with {@code deletedSince}: messages deleted since then, already shown to the
	 * user, which they should now show as deleted; empty otherwise
	 * @param nextDeletedSince the {@code deletedSince} to pass in the next poll
	 */
	@Schema(name = "MessagePage")
	record MessagePage(List<MessageResponse> items, boolean hasMore, List<UUID> deletedIds,
			Instant nextDeletedSince) {
	}

	@Schema(name = "UnreadMessages")
	record UnreadCountResponse(long count) {
	}

	record SendMessageRequest(@NotBlank @Size(max = MAX_MESSAGE_LENGTH) String body) {
	}

	/** @param customerId one of the signed-in trainer's clients, see {@link ClientResponse} */
	record StartTraineeConversationRequest(@NotNull UUID customerId) {
	}

	/** @param staffId an active trainer */
	record StartTrainerConversationRequest(@NotNull UUID staffId) {
	}

	/**
	 * Exactly one of {@code trainingGroupId} and {@code eventId}.
	 *
	 * @param title the chat's name; the group's or class's name when blank
	 */
	record CreateGroupChatRequest(@Nullable UUID trainingGroupId, @Nullable UUID eventId,
			@Nullable @Size(max = MAX_TITLE_LENGTH) String title, boolean clientsCanReply) {
	}

	record UpdateConversationRequest(@NotNull Boolean clientsCanReply) {
	}

	/**
	 * A trainer a client can write to.
	 *
	 * @param conversationId the client's existing conversation with the trainer, or {@code null}
	 */
	@Schema(name = "MessageableTrainer")
	record TrainerResponse(UUID staffId, @Schema(example = "Anna Wiśniewska") String displayName,
			@Nullable UUID conversationId) {
	}

	/**
	 * A client a trainer can write to: a participant assigned to the trainer, in a fixed group the
	 * trainer runs, or booked on a class the trainer runs.
	 *
	 * @param isTrainee whether the participant is assigned to the trainer for personal training
	 * @param classes names of the trainer's groups and classes the participant takes part in, sorted
	 * @param conversationId the trainer's existing conversation with the participant's account (for a
	 * child, the guardian's), or {@code null}
	 */
	@Schema(name = "MessageableClient")
	record ClientResponse(UUID customerId, @Schema(example = "Marta Zielińska") String displayName, boolean isChild,
			boolean isTrainee, List<String> classes, @Nullable UUID conversationId) {
	}

	/** Fixed groups and upcoming classes the signed-in trainer runs, for a new chat. */
	@Schema(name = "GroupChatTargets")
	record GroupChatTargets(List<GroupTarget> groups, List<EventTarget> events) {
	}

	/** @param conversationId the group's chat, or {@code null} when it has none yet */
	@Schema(name = "GroupChatTarget")
	record GroupTarget(UUID trainingGroupId, String name, int memberCount, @Nullable UUID conversationId) {
	}

	/** @param conversationId the class's chat, or {@code null} when it has none yet */
	@Schema(name = "EventChatTarget")
	record EventTarget(UUID eventId, String title, Instant startsAt, int participantCount,
			@Nullable UUID conversationId) {
	}

}
