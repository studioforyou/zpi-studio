package studioforyou.pl.backend.documents.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.documents.DocumentAcceptanceStatus;
import studioforyou.pl.backend.documents.internal.DocumentRepository.DocumentState;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Documents and consents of the profiles an account acts for: its own customer profile and
 * the children it is a guardian of. A guardian accepts documents on the child's behalf.
 */
@Service
@Transactional
public class OwnDocumentService {

	private final DocumentRepository documents;

	private final CustomerProfiles customers;

	private final ServiceTypes serviceTypes;

	public OwnDocumentService(DocumentRepository documents, CustomerProfiles customers, ServiceTypes serviceTypes) {
		this.documents = documents;
		this.customers = customers;
		this.serviceTypes = serviceTypes;
	}

	@Transactional(readOnly = true)
	public List<OwnDocument> list(UUID accountId, UUID profileId) {
		List<DocumentState> states = documents.findStates(profileId, actingKind(accountId, profileId));
		return withServiceNames(states, null);
	}

	@Transactional(readOnly = true)
	public OwnDocument get(UUID accountId, UUID profileId, UUID documentId) {
		DocumentState state = find(accountId, profileId, documentId);
		return withServiceNames(List.of(state), documents.findContent(state.versionId())).get(0);
	}

	/**
	 * Accepts the version in force for the profile. The caller names the version it showed to
	 * the user, so a version published in the meantime is never accepted unread.
	 */
	public OwnDocument accept(UUID accountId, UUID profileId, UUID documentId, UUID versionId) {
		DocumentState state = find(accountId, profileId, documentId);
		if (!state.versionId().equals(versionId)) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"A newer version of the document was published. Read it before accepting.");
		}
		if (state.acceptedVersionNo() != null && state.acceptedVersionNo() == state.versionNo()) {
			throw alreadyAccepted();
		}
		try {
			documents.insertAcceptance(versionId, profileId, accountId);
		}
		catch (DuplicateKeyException ex) {
			// The same version was accepted at the same moment, e.g. by the other guardian.
			throw alreadyAccepted();
		}
		return get(accountId, profileId, documentId);
	}

	/**
	 * Withdraws an optional consent. Withdrawing a consent that is not given changes nothing.
	 */
	public OwnDocument revoke(UUID accountId, UUID profileId, UUID documentId) {
		DocumentState state = find(accountId, profileId, documentId);
		if (state.required()) {
			throw new ApiException(ErrorCode.DOCUMENT_REVOCATION_NOT_ALLOWED,
					"A required document cannot be withdrawn in the app. Contact the studio.");
		}
		documents.revokeAcceptances(documentId, profileId);
		return get(accountId, profileId, documentId);
	}

	private DocumentState find(UUID accountId, UUID profileId, UUID documentId) {
		return documents.findState(profileId, actingKind(accountId, profileId), documentId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Document not found."));
	}

	/**
	 * The kind of a profile the account acts for. Another profile is reported as not found,
	 * so its existence is not revealed.
	 */
	private CustomerKind actingKind(UUID accountId, UUID profileId) {
		if (!customers.isActingFor(accountId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return customers.findById(profileId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."))
			.kind();
	}

	private List<OwnDocument> withServiceNames(List<DocumentState> states, String content) {
		List<UUID> ids = states.stream().flatMap((state) -> state.requiredForServiceIds().stream()).distinct().toList();
		Map<UUID, ServiceTypeSummary> services = ids.isEmpty() ? Map.of()
				: serviceTypes.findAllById(ids)
					.stream()
					.collect(Collectors.toMap(ServiceTypeSummary::id, Function.identity()));
		return states.stream()
			.map((state) -> new OwnDocument(state,
					state.requiredForServiceIds().stream().map(services::get).filter((s) -> s != null).toList(),
					content))
			.toList();
	}

	private static ApiException alreadyAccepted() {
		return new ApiException(ErrorCode.DOCUMENT_ALREADY_ACCEPTED, "This version of the document is already accepted.");
	}

	/**
	 * A document in force for a profile.
	 * @param requiredFor the services it is required for; empty when required for all
	 * @param content the text of the version in force; {@code null} in lists and when the version is a file
	 */
	public record OwnDocument(DocumentState state, List<ServiceTypeSummary> requiredFor, String content) {

		public DocumentAcceptanceStatus status() {
			return state.status();
		}

	}

}
