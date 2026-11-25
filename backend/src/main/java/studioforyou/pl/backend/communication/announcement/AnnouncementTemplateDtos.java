package studioforyou.pl.backend.communication.announcement;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/** Requests and responses of the administrator's announcement templates. */
final class AnnouncementTemplateDtos {

	static final int NAME_MAX = 100;

	private AnnouncementTemplateDtos() {
	}

	@Schema(description = "A new announcement template. Title and text have the limits of a sent announcement.")
	record CreateTemplateRequest(@NotBlank @Size(max = NAME_MAX) String name,
			@NotBlank @Size(max = AnnouncementDtos.TITLE_MAX) String title,
			@NotBlank @Size(max = AnnouncementDtos.BODY_MAX) String body,
			@Schema(description = "An offer for people with the MARKETING_PUSH consent; false (a studio message) when missing.")
			@Nullable Boolean marketing) {
	}

	@Schema(description = "Changes to an announcement template; omitted fields stay as they are.")
	record UpdateTemplateRequest(@Nullable @Size(min = 1, max = NAME_MAX) String name,
			@Nullable @Size(min = 1, max = AnnouncementDtos.TITLE_MAX) String title,
			@Nullable @Size(min = 1, max = AnnouncementDtos.BODY_MAX) String body, @Nullable Boolean marketing) {
	}

	@Schema(description = "Reusable announcement content.")
	record AnnouncementTemplate(UUID id, String name, String title, String body,
			@Schema(description = "An offer (\"Oferta\" in the app) rather than a studio message (\"Komunikat studia\").")
			boolean marketing, Instant updatedAt) {
	}

}
