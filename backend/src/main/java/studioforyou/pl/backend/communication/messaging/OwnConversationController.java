package studioforyou.pl.backend.communication.messaging;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import studioforyou.pl.backend.communication.messaging.MessagingDtos.AttachmentPage;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ClientResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ConversationResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.ConversationSummaryResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.CreateGroupChatRequest;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.GroupChatTargets;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.MessagePage;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.MessageResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.SendMessageRequest;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.StartTraineeConversationRequest;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.StartTrainerConversationRequest;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.TrainerResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.UnreadCountResponse;
import studioforyou.pl.backend.communication.messaging.MessagingDtos.UpdateConversationRequest;
import studioforyou.pl.backend.communication.messaging.MessagingService.NewAttachment;
import studioforyou.pl.backend.communication.messaging.MessagingService.Started;
import studioforyou.pl.backend.files.StoredFiles;
import studioforyou.pl.backend.files.StoredFiles.StoredFile;
import studioforyou.pl.backend.files.StoredFiles.Thumbnail;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * The signed-in user's conversations: 1:1 conversations between clients and trainers, and the
 * chats of fixed groups and classes that trainers create.
 */
@RestController
@RequestMapping("/api/v1/me/conversations")
@Tag(name = "communication")
@PreAuthorize("hasAnyRole('CLIENT', 'TRAINER')")
class OwnConversationController {

	private static final String BASE = "/api/v1/me/conversations/";

	/** Files never change; only the user's own device keeps them. */
	private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(7)).cachePrivate();

	private final MessagingService messaging;

	OwnConversationController(MessagingService messaging) {
		this.messaging = messaging;
	}

	@GetMapping
	@Operation(summary = "List my conversations", operationId = "listOwnConversations",
			description = "1:1 conversations and the group and class chats the user belongs to or left, most "
					+ "recently active first. Joining and leaving group and class chats follows the schedule, "
					+ "bookings and fixed groups, so the list is brought up to date on every call.")
	@ApiResponse(responseCode = "200", description = "The user's conversations.")
	ListResponse<ConversationSummaryResponse> list(AccountAuthentication authentication) {
		return new ListResponse<>(messaging.list(authentication.requireActiveAccountId()));
	}

	@GetMapping("/unread-count")
	@Operation(summary = "Count my unread messages", operationId = "countOwnUnreadMessages")
	@ApiResponse(responseCode = "200", description = "Unread messages in all the user's conversations.")
	UnreadCountResponse unreadCount(AccountAuthentication authentication) {
		return new UnreadCountResponse(messaging.countUnread(authentication.requireActiveAccountId()));
	}

	@GetMapping("/trainers")
	@PreAuthorize("hasRole('CLIENT')")
	@Operation(summary = "List trainers I can write to", operationId = "listMessageableTrainers",
			description = "Active trainers, sorted by name, with the client's existing conversation with each.")
	@ApiResponse(responseCode = "200", description = "The trainers.")
	ListResponse<TrainerResponse> trainers(AccountAuthentication authentication) {
		return new ListResponse<>(messaging.trainers(authentication.requireActiveAccountId()));
	}

	@GetMapping("/clients")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "List clients I can write to", operationId = "listMessageableClients",
			description = "Participants assigned to the trainer, in the fixed groups the trainer runs or booked on "
					+ "the trainer's classes from the last and next 30 days, sorted by name, with the trainer's "
					+ "existing conversation with each. Participants without an account to write to are left out.")
	@ApiResponse(responseCode = "200", description = "The clients.")
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ListResponse<ClientResponse> clients(AccountAuthentication authentication) {
		return new ListResponse<>(messaging.clients(authentication.requireActiveAccountId()));
	}

	@GetMapping("/group-chat-targets")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "List groups and classes I can create a chat for", operationId = "listGroupChatTargets",
			description = "Fixed groups whose upcoming classes the trainer runs, and the trainer's classes and "
					+ "workshops in the next 30 days, each with its chat if it already has one.")
	@ApiResponse(responseCode = "200", description = "The groups and classes.")
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	GroupChatTargets groupChatTargets(AccountAuthentication authentication) {
		return messaging.groupChatTargets(authentication.requireActiveAccountId());
	}

	@PostMapping("/trainee")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "Write to my client", operationId = "startTraineeConversation",
			description = "Opens the 1:1 conversation with one of the trainer's clients (see "
					+ "listMessageableClients), creating it if needed. For a child, the conversation is with the "
					+ "child's guardian.")
	@ApiResponse(responseCode = "200", description = "The existing conversation.")
	@ApiResponse(responseCode = "201", description = "The new conversation.")
	@ApiResponse(responseCode = "404", description = "The participant is not the trainer's client, or the "
			+ "account has no staff profile.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "RECIPIENT_WITHOUT_ACCOUNT.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ConversationResponse> startWithTrainee(@Valid @RequestBody StartTraineeConversationRequest request,
			AccountAuthentication authentication) {
		return started(messaging.startWithTrainee(authentication.requireActiveAccountId(), request.customerId()));
	}

	@PostMapping("/trainer")
	@PreAuthorize("hasRole('CLIENT')")
	@Operation(summary = "Write to a trainer", operationId = "startTrainerConversation",
			description = "Opens the client's 1:1 conversation with any active trainer, creating it if needed.")
	@ApiResponse(responseCode = "200", description = "The existing conversation.")
	@ApiResponse(responseCode = "201", description = "The new conversation.")
	@ApiResponse(responseCode = "422", description = "STAFF_UNAVAILABLE or STAFF_NOT_TRAINER.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ConversationResponse> startWithTrainer(@Valid @RequestBody StartTrainerConversationRequest request,
			AccountAuthentication authentication) {
		return started(messaging.startWithTrainer(authentication.requireActiveAccountId(), request.staffId()));
	}

	@PostMapping("/group-chats")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "Create a group or class chat", operationId = "createGroupChat",
			description = "Creates the chat of a fixed group or a class the trainer runs. Its members follow the "
					+ "group and bookings; the trainer decides whether clients may reply.")
	@ApiResponse(responseCode = "201", description = "The new chat.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "The trainer does not run the group or class.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "CONVERSATION_ALREADY_EXISTS.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CONVERSATION_READ_ONLY (the class has ended or was "
			+ "cancelled).", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ConversationResponse> createGroupChat(@Valid @RequestBody CreateGroupChatRequest request,
			AccountAuthentication authentication) {
		ConversationResponse created = messaging.createGroupChat(authentication.requireActiveAccountId(), request);
		return ResponseEntity.created(URI.create(BASE + created.id())).body(created);
	}

	@GetMapping("/{conversationId}")
	@Operation(summary = "Get my conversation", operationId = "getOwnConversation")
	@ApiResponse(responseCode = "200", description = "The conversation and what the user may do in it.")
	@ApiResponse(responseCode = "404", description = "No such conversation, or the user has never been in it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ConversationResponse get(@PathVariable UUID conversationId, AccountAuthentication authentication) {
		return messaging.get(conversationId, authentication.requireActiveAccountId());
	}

	@PatchMapping("/{conversationId}")
	@Operation(summary = "Change a group or class chat", operationId = "updateOwnConversation",
			description = "Only the staff running the group or class.")
	@ApiResponse(responseCode = "200", description = "The changed chat.")
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such conversation, or the user has never been in it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ConversationResponse update(@PathVariable UUID conversationId, @Valid @RequestBody UpdateConversationRequest request,
			AccountAuthentication authentication) {
		return messaging.update(conversationId, authentication.requireActiveAccountId(), request.clientsCanReply());
	}

	@DeleteMapping("/{conversationId}")
	@Operation(summary = "Remove a chat from my list", operationId = "hideOwnConversation",
			description = "Only a group or class chat the user no longer belongs to. Other people keep it.")
	@ApiResponse(responseCode = "204", description = "The chat is no longer on the user's list.")
	@ApiResponse(responseCode = "404", description = "No such conversation, or the user has never been in it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CONVERSATION_STILL_ACTIVE.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<Void> hide(@PathVariable UUID conversationId, AccountAuthentication authentication) {
		messaging.hide(conversationId, authentication.requireActiveAccountId());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{conversationId}/read")
	@Operation(summary = "Mark my conversation as read", operationId = "markOwnConversationRead")
	@ApiResponse(responseCode = "204", description = "All messages so far are read.")
	@ApiResponse(responseCode = "404", description = "No such conversation, or the user has never been in it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<Void> markRead(@PathVariable UUID conversationId, AccountAuthentication authentication) {
		messaging.markRead(conversationId, authentication.requireActiveAccountId());
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{conversationId}/messages")
	@Operation(summary = "List messages", operationId = "listOwnConversationMessages",
			description = "Messages oldest first: the latest page, the page before a message (older history) or "
					+ "the messages after one (polling for new ones). A user who left a group or class sees only "
					+ "the messages from before they left.")
	@ApiResponse(responseCode = "200", description = "The messages.")
	@ApiResponse(responseCode = "404", description = "No such conversation or cursor message, or the user has never "
			+ "been in the conversation.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	MessagePage messages(@PathVariable UUID conversationId,
			@Parameter(description = "Messages older than this one.") @RequestParam(required = false) @Nullable UUID before,
			@Parameter(description = "Messages newer than this one.") @RequestParam(required = false) @Nullable UUID after,
			@Parameter(description = "Also list the messages deleted since then: the nextDeletedSince of the "
					+ "previous page.") @RequestParam(required = false) @Nullable Instant deletedSince,
			@Parameter(description = "Page size, 1 to 100.") @RequestParam(defaultValue = "50") int limit,
			AccountAuthentication authentication) {
		return messaging.messages(conversationId, authentication.requireActiveAccountId(), before, after, deletedSince,
				limit);
	}

	@PostMapping(path = "/{conversationId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "Send a message", operationId = "sendOwnConversationMessage",
			description = "A text message. To attach files, send the message as multipart/form-data.")
	@ApiResponse(responseCode = "201", description = "The sent message.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such conversation, or the user has never been in it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CONVERSATION_READ_ONLY: clients may not reply, the user left "
			+ "the group or class, or the class has ended.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<MessageResponse> send(@PathVariable UUID conversationId, @Valid @RequestBody SendMessageRequest request,
			AccountAuthentication authentication) {
		return sent(conversationId,
				messaging.send(conversationId, authentication.requireActiveAccountId(), request.body(), List.of()));
	}

	@PostMapping(path = "/{conversationId}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Send a message with attachments", operationId = "sendOwnConversationMessageWithAttachments",
			description = "Up to 5 files (part `files`) of at most 10 MB each and 30 MB together, with optional text "
					+ "(part `body`, up to 2000 characters): JPEG, PNG and HEIC photos, PDF, DOCX, XLSX and TXT "
					+ "documents. The type is recognized from the content, and the file name gets its extension. "
					+ "Either the text or a file is required.")
	@ApiResponse(responseCode = "201", description = "The sent message.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: no text and no file, text too long, "
			+ "too many files or an empty file.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such conversation, or the user has never been in it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CONVERSATION_READ_ONLY, or FILE_TYPE_NOT_ALLOWED (with "
			+ "`allowedTypes`).", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<MessageResponse> sendWithAttachments(@PathVariable UUID conversationId,
			@RequestParam(required = false) @Nullable String body,
			@RequestPart(name = "files", required = false) @Nullable List<MultipartFile> files,
			AccountAuthentication authentication) {
		List<NewAttachment> attachments = (files == null) ? List.of()
				: files.stream()
					.map((file) -> new NewAttachment(file.getOriginalFilename(), file.getSize(), file))
					.toList();
		return sent(conversationId,
				messaging.send(conversationId, authentication.requireActiveAccountId(), body, attachments));
	}

	@GetMapping("/{conversationId}/attachments")
	@Operation(summary = "List the photos or files of my conversation", operationId = "listOwnConversationAttachments",
			description = "The photos (kind PHOTO) or the other files (kind FILE) sent in the conversation, each with "
					+ "the message it came with, oldest first: the latest page, the page before an attachment "
					+ "(older ones) or the page after one (newer ones). Deleted messages are left out; a user who "
					+ "left a group or class sees only what was sent before they left.")
	@ApiResponse(responseCode = "200", description = "The photos or files.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: both before and after.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such conversation or cursor attachment, or the user has "
			+ "never been in the conversation.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	AttachmentPage attachments(@PathVariable UUID conversationId,
			@Parameter(description = "Photos or other files.") @RequestParam AttachmentKind kind,
			@Parameter(description = "Sent before this attachment.") @RequestParam(required = false) @Nullable UUID before,
			@Parameter(description = "Sent after this attachment.") @RequestParam(required = false) @Nullable UUID after,
			@Parameter(description = "Page size, 1 to 100.") @RequestParam(defaultValue = "50") int limit,
			AccountAuthentication authentication) {
		return messaging.attachments(conversationId, authentication.requireActiveAccountId(), kind, before, after,
				limit);
	}

	@GetMapping("/{conversationId}/messages/{messageId}/attachments/{attachmentId}")
	@Operation(summary = "Download an attachment", operationId = "getOwnConversationMessageAttachment",
			description = "The content of a file attached to a message the user can see, with its media type. "
					+ "Photos are served inline, other files as attachments with their file name. The content "
					+ "never changes, so the app may keep it.")
	@ApiResponse(responseCode = "200", description = "The file.", content = @Content(
			mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
	@ApiResponse(responseCode = "404", description = "No such conversation, message or attachment, the message "
			+ "is deleted, or the user cannot see it.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<Resource> attachment(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			@PathVariable UUID attachmentId, AccountAuthentication authentication) {
		StoredFiles.Content content = messaging.attachment(conversationId, messageId, attachmentId,
				authentication.requireActiveAccountId());
		StoredFile file = content.file();
		ContentDisposition.Builder disposition = file.isImage() ? ContentDisposition.inline()
				: ContentDisposition.attachment();
		// A name with letters outside ASCII, e.g. "Plan na tydzień.docx", goes as UTF-8 (filename*).
		if (StandardCharsets.US_ASCII.newEncoder().canEncode(file.name())) {
			disposition.filename(file.name());
		}
		else {
			disposition.filename(file.name(), StandardCharsets.UTF_8);
		}
		MediaType type = MediaType.parseMediaType(file.mimeType());
		if ("text".equals(type.getType())) {
			type = new MediaType(type, StandardCharsets.UTF_8);
		}
		return ResponseEntity.ok()
			.contentType(type)
			.contentLength(file.sizeBytes())
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.build().toString())
			.cacheControl(CACHE)
			.body(new InputStreamResource(content.stream()));
	}

	@GetMapping("/{conversationId}/messages/{messageId}/attachments/{attachmentId}/thumbnail")
	@Operation(summary = "Download the thumbnail of a photo", operationId = "getOwnConversationMessageAttachmentThumbnail",
			description = "A JPEG of at most 1024 px on its longer side, upright and without metadata, to show in the "
					+ "conversation and its photos instead of the whole photo; only for an attachment with "
					+ "`hasThumbnail`. Who may see it is as for the attachment. The content never changes.")
	@ApiResponse(responseCode = "200", description = "The thumbnail.", content = @Content(mediaType = "image/jpeg",
			schema = @Schema(type = "string", format = "binary")))
	@ApiResponse(responseCode = "404", description = "As for the attachment, or the attachment has no thumbnail.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<Resource> thumbnail(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			@PathVariable UUID attachmentId, AccountAuthentication authentication) {
		Thumbnail thumbnail = messaging.thumbnail(conversationId, messageId, attachmentId,
				authentication.requireActiveAccountId());
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(Thumbnail.MIME_TYPE))
			.contentLength(thumbnail.sizeBytes())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
			.cacheControl(CACHE)
			.body(new InputStreamResource(thumbnail.stream()));
	}

	@DeleteMapping("/{conversationId}/messages/{messageId}")
	@Operation(summary = "Delete a message", operationId = "deleteOwnConversationMessage",
			description = "The user's own message, or anyone's for the staff running the group or class. The "
					+ "message keeps its place as deleted.")
	@ApiResponse(responseCode = "204", description = "The message is deleted.")
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such conversation or message, or the user has never been "
			+ "in the conversation.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<Void> deleteMessage(@PathVariable UUID conversationId, @PathVariable UUID messageId,
			AccountAuthentication authentication) {
		messaging.deleteMessage(conversationId, messageId, authentication.requireActiveAccountId());
		return ResponseEntity.noContent().build();
	}

	private static ResponseEntity<MessageResponse> sent(UUID conversationId, MessageResponse sent) {
		return ResponseEntity.created(URI.create(BASE + conversationId + "/messages/" + sent.id())).body(sent);
	}

	private static ResponseEntity<ConversationResponse> started(Started started) {
		ConversationResponse conversation = started.conversation();
		if (started.created()) {
			return ResponseEntity.created(URI.create(BASE + conversation.id())).body(conversation);
		}
		return ResponseEntity.ok(conversation);
	}

}
