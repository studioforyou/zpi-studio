package studioforyou.pl.backend.customer;

import java.util.UUID;

/**
 * Trainer-participant cooperation for other modules.
 */
public interface OwnTrainees {

	/**
	 * Starts the trainer's cooperation with the participant, with the trainer recorded as the one
	 * who made the assignment. Same rules as when the trainer assigns a participant in the app.
	 *
	 * @param trainerAccountId the signed-in trainer's account
	 * @throws studioforyou.pl.backend.shared.api.ApiException {@code NOT_FOUND} if the account has
	 * no staff profile, {@code STAFF_UNAVAILABLE} if it is not active
	 */
	void assignToTrainer(UUID customerId, UUID trainerAccountId);

}
