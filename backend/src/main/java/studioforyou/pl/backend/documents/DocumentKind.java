package studioforyou.pl.backend.documents;

/**
 * Kind of a document or consent ({@code document.kind}).
 */
public enum DocumentKind {

	/** The studio's terms of service. */
	TERMS,
	/** The personal data processing notice and consent. */
	GDPR,
	/** Consent to take part in classes, e.g. a risk statement for pole dance. */
	PARTICIPATION_CONSENT,
	/** A guardian's consent for a child to take part in classes. */
	PARENTAL_CONSENT,
	/** A health questionnaire; filled in rather than accepted, so it is not listed with the other documents. */
	HEALTH_QUESTIONNAIRE,
	/** Optional consent to marketing e-mails. */
	MARKETING_EMAIL,
	/** Optional consent to marketing text messages. */
	MARKETING_SMS

}
