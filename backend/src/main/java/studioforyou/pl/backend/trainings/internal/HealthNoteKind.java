package studioforyou.pl.backend.trainings.internal;

/** What a health note is about; stored in {@code health_note.kind}. */
enum HealthNoteKind {

	/** An injury, e.g. a knee injury from 2023. */
	INJURY,

	/** A lasting condition, e.g. exercise-induced asthma. */
	CONDITION,

	/** Something the participant must not do, e.g. no hanging for two weeks. */
	CONTRAINDICATION,

	OTHER

}
