package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * @param customerId the participant the signed-in trainer starts working with
 */
public record AssignTraineeRequest(@NotNull UUID customerId) {
}
