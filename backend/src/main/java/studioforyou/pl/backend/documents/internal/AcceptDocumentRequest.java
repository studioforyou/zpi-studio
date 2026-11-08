package studioforyou.pl.backend.documents.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Acceptance of the document version shown to the user.
 *
 * @param versionId the version in force, as returned with the document
 */
@Schema(name = "AcceptDocumentRequest")
public record AcceptDocumentRequest(@NotNull UUID versionId) {
}
