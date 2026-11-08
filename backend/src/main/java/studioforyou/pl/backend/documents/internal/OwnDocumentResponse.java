package studioforyou.pl.backend.documents.internal;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.documents.DocumentAcceptanceStatus;
import studioforyou.pl.backend.documents.DocumentKind;
import studioforyou.pl.backend.documents.internal.DocumentRepository.DocumentState;
import studioforyou.pl.backend.documents.internal.OwnDocumentService.OwnDocument;

/**
 * A document or consent in force for a participant, with the participant's acceptance.
 *
 * @param required whether it has to be accepted; {@code false} for optional consents
 * @param blocksBooking whether classes cannot be booked until it is accepted
 * @param requiredFor the services it is required for; empty when required for all
 * @param versionId the version in force, the one to accept
 * @param hasFile whether the version's content is a file rather than text
 * @param acceptedVersionNo the latest accepted and not withdrawn version, or {@code null}
 * @param acceptedAt when that version was accepted, or {@code null}
 * @param revocable whether the participant may withdraw the consent: optional and given
 * @param content the version's text; only when getting one document, {@code null} for a file
 */
@Schema(name = "OwnDocument")
public record OwnDocumentResponse(UUID id, @Schema(example = "Regulamin studia") String title, DocumentKind kind,
		boolean required, boolean blocksBooking, List<ServiceRef> requiredFor, UUID versionId,
		@Schema(example = "2") int versionNo, OffsetDateTime publishedAt, boolean hasFile,
		DocumentAcceptanceStatus status, Integer acceptedVersionNo, OffsetDateTime acceptedAt, boolean revocable,
		String content) {

	static OwnDocumentResponse from(OwnDocument document) {
		DocumentState state = document.state();
		return new OwnDocumentResponse(state.id(), state.title(), state.kind(), state.required(),
				state.blocksBooking(),
				document.requiredFor().stream().map((service) -> new ServiceRef(service.id(), service.name())).toList(),
				state.versionId(), state.versionNo(), state.publishedAt(), state.hasFile() && !state.hasContent(),
				document.status(), state.acceptedVersionNo(), state.acceptedAt(),
				!state.required() && state.acceptedVersionNo() != null, document.content());
	}

	@Schema(name = "DocumentServiceRef")
	public record ServiceRef(UUID id, @Schema(example = "Pole dance") String name) {
	}

}
