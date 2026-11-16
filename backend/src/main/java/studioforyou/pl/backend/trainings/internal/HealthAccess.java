package studioforyou.pl.backend.trainings.internal;

/**
 * Whether the signed-in trainer may see and keep a participant's health notes. Access needs both
 * the {@code CUSTOMER_HEALTH_READ} permission and the participant's consent on the trainer's
 * assignment ({@code staff_assignment.can_view_health}).
 */
enum HealthAccess {

	GRANTED,

	/** The trainer has the permission, but the participant has not shared their health data with them. */
	NOT_SHARED,

	/** The trainer does not have the {@code CUSTOMER_HEALTH_READ} permission, whatever the participant shared. */
	NO_PERMISSION

}
