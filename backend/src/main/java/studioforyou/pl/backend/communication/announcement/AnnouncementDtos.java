package studioforyou.pl.backend.communication.announcement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/** Requests and responses of the administrator's announcements. */
final class AnnouncementDtos {

	static final int TITLE_MAX = 160;

	static final int BODY_MAX = 2000;

	private AnnouncementDtos() {
	}

	@Schema(description = "Who an announcement is for. Exactly the field matching the audience is used: "
			+ "accountId for PERSON, trainingGroupId for GROUP, eventId for EVENT, none for ALL.")
	record AudienceRequest(@NotNull AnnouncementAudience audience, @Nullable UUID accountId,
			@Nullable UUID trainingGroupId, @Nullable UUID eventId,
			@Schema(description = "Marketing content: only people with the MARKETING_PUSH consent get it; false when missing.")
			@Nullable Boolean marketing) {

		boolean isMarketing() {
			return Boolean.TRUE.equals(marketing);
		}

	}

	record SendAnnouncementRequest(@NotNull AnnouncementAudience audience, @Nullable UUID accountId,
			@Nullable UUID trainingGroupId, @Nullable UUID eventId, @Nullable Boolean marketing,
			@NotBlank @Size(max = TITLE_MAX) String title, @NotBlank @Size(max = BODY_MAX) String body) {

		boolean isMarketing() {
			return Boolean.TRUE.equals(marketing);
		}

		AudienceRequest audienceRequest() {
			return new AudienceRequest(audience, accountId, trainingGroupId, eventId, marketing);
		}

	}

	@Schema(description = "Who an announcement would reach right now.")
	record AnnouncementPreview(
			@Schema(description = "People who would get it.") int recipients,
			@Schema(description = "Of them, people who get it only as the guardian of a child in the audience.") int guardians,
			@Schema(description = "People left out of a marketing announcement for lack of consent.") int skippedWithoutConsent) {
	}

	@Schema(description = "A sent announcement with its delivery figures.")
	record AnnouncementSummary(UUID id, String title, String body, AnnouncementAudience audience,
			@Schema(description = "The person, group or class it was for; null for ALL.") @Nullable String targetName,
			@Schema(description = "Start of the class for EVENT.") @Nullable Instant eventStartsAt, boolean marketing,
			Instant sentAt, @Nullable String authorName,
			@Schema(description = "People who got it.") int recipients,
			@Schema(description = "Recipients who read it in the app.") int read,
			@Schema(description = "Recipients a push reached on at least one device.") int pushDelivered,
			@Schema(description = "People left out of a marketing announcement for lack of consent.") int skippedWithoutConsent) {
	}

	record AnnouncementDetails(AnnouncementSummary announcement, List<AnnouncementRecipient> recipients) {
	}

	@Schema(description = "One person who got an announcement.")
	record AnnouncementRecipient(UUID accountId, String name,
			@Schema(description = "The children it was about when the person got it as a guardian.") @Nullable String about,
			RecipientStatus status) {
	}

	enum RecipientStatus {

		/** Opened in the app. */
		READ,

		/** A push reached a device; not opened yet. */
		DELIVERED,

		/** Only in the in-app inbox: no device, or the push failed. */
		APP_ONLY

	}

}
