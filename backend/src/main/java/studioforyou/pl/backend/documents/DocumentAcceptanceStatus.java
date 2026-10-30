package studioforyou.pl.backend.documents;

/**
 * Whether a participant has accepted a document, as of now.
 */
public enum DocumentAcceptanceStatus {

	/** The current version, or an earlier one that a later version did not ask to accept again. */
	ACCEPTED,
	/** An earlier version was accepted, but a later published version has to be accepted again. */
	REACCEPTANCE_REQUIRED,
	/** No version is accepted, or the consent was withdrawn. */
	NOT_ACCEPTED

}
