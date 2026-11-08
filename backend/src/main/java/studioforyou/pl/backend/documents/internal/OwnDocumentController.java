package studioforyou.pl.backend.documents.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Documents and consents of the profiles the signed-in account acts for: its own customer
 * profile and the children it is a guardian of.
 */
@RestController
@RequestMapping("/api/v1/me/profiles/{profileId}/documents")
@Tag(name = "documents")
public class OwnDocumentController {

	private final OwnDocumentService documents;

	public OwnDocumentController(OwnDocumentService documents) {
		this.documents = documents;
	}

	@GetMapping
	@Operation(summary = "List my documents and consents", operationId = "listOwnDocuments",
			description = "Active documents with a published version, meant for the profile (adult or child): "
					+ "required ones first, then optional consents. Each shows the version in force and whether "
					+ "the profile accepted it. Without content; get one document to read it.")
	@ApiResponse(responseCode = "200", description = "The profile's documents.")
	@ApiResponse(responseCode = "404", description = "No such profile, or the account does not act for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnDocumentListResponse list(@PathVariable UUID profileId, Authentication authentication) {
		return new OwnDocumentListResponse(documents.list(CurrentAccount.of(authentication).id(), profileId)
			.stream()
			.map(OwnDocumentResponse::from)
			.toList());
	}

	@GetMapping("/{documentId}")
	@Operation(summary = "Read one of my documents", operationId = "getOwnDocument",
			description = "The document with the text of the version in force.")
	@ApiResponse(responseCode = "200", description = "The document.")
	@ApiResponse(responseCode = "404", description = "No such profile or document for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnDocumentResponse get(@PathVariable UUID profileId, @PathVariable UUID documentId,
			Authentication authentication) {
		return OwnDocumentResponse
			.from(documents.get(CurrentAccount.of(authentication).id(), profileId, documentId));
	}

	@PostMapping("/{documentId}/acceptances")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Accept a document or give a consent", operationId = "acceptOwnDocument",
			description = "Records that the profile accepts the version in force; a guardian accepts for a child. "
					+ "Earlier acceptances stay in the history.")
	@ApiResponse(responseCode = "201", description = "The document, now accepted.")
	@ApiResponse(responseCode = "404", description = "No such profile or document for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT when the version is no longer in force, "
			+ "DOCUMENT_ALREADY_ACCEPTED when it is already accepted.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public OwnDocumentResponse accept(@PathVariable UUID profileId, @PathVariable UUID documentId,
			@Valid @RequestBody AcceptDocumentRequest request, Authentication authentication) {
		return OwnDocumentResponse.from(documents.accept(CurrentAccount.of(authentication).id(), profileId,
				documentId, request.versionId()));
	}

	@DeleteMapping("/{documentId}/acceptances")
	@Operation(summary = "Withdraw a consent", operationId = "revokeOwnDocument",
			description = "Withdraws an optional consent, e.g. to marketing e-mails. The acceptance stays in "
					+ "the history as withdrawn. Withdrawing a consent that is not given changes nothing.")
	@ApiResponse(responseCode = "200", description = "The document, now not accepted.")
	@ApiResponse(responseCode = "404", description = "No such profile or document for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "DOCUMENT_REVOCATION_NOT_ALLOWED for a required document.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnDocumentResponse revoke(@PathVariable UUID profileId, @PathVariable UUID documentId,
			Authentication authentication) {
		return OwnDocumentResponse.from(documents.revoke(CurrentAccount.of(authentication).id(), profileId,
				documentId));
	}

}
