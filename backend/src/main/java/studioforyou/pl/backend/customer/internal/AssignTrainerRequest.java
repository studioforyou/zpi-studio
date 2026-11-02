package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * @param staffId the staff profile of the trainer to assign
 */
public record AssignTrainerRequest(@NotNull UUID staffId) {
}
