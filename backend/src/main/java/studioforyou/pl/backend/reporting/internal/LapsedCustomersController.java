package studioforyou.pl.backend.reporting.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.membership.PassHolders.CurrentPass;
import studioforyou.pl.backend.reporting.internal.LapsedCustomersReport.LapsedCustomer;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Customers who stopped coming, so that administrators can contact them (#171).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/reports")
@Tag(name = "reporting")
class LapsedCustomersController {

	static final int MAX_INACTIVE_DAYS = 365;

	private final LapsedCustomersReport report;

	LapsedCustomersController(LapsedCustomersReport report) {
		this.report = report;
	}

	@GetMapping("/lapsed-customers")
	@Operation(summary = "List customers who stopped coming", operationId = "getLapsedCustomers",
			description = "Participants (adults and children) who visited the studio at least once, whose last "
					+ "visit was at least inactiveDays studio days ago and who have no reservation for an upcoming "
					+ "event. A visit is a reservation that was not cancelled and not marked absent for an event "
					+ "that started and was not cancelled by the studio. Archived profiles and adults whose account "
					+ "is not active are left out. A child is reached through its earliest current guardian. "
					+ "Sorted from the longest absent. As of now: no period applies.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when inactiveDays is not between 1 and 365.")
	LapsedCustomersResponse lapsedCustomers(
			@Parameter(description = "Minimum number of days since the last visit.", example = "30")
			@RequestParam(defaultValue = "30") int inactiveDays) {
		if (inactiveDays < 1 || inactiveDays > MAX_INACTIVE_DAYS) {
			throw ApiException.invalidField("inactiveDays", FieldErrorCode.OUT_OF_RANGE,
					"inactiveDays must be between 1 and " + MAX_INACTIVE_DAYS + ".");
		}
		LapsedCustomersReport.Result result = report.find(inactiveDays, Instant.now());
		return new LapsedCustomersResponse(result.asOf(), result.inactiveDays(), result.everVisited(),
				result.customers().stream().map(LapsedCustomerResponse::from).toList());
	}

	@Schema(name = "LapsedCustomersReport")
	record LapsedCustomersResponse(@Schema(description = "Today, in the studio's time zone.") LocalDate asOf,
			int inactiveDays,
			@Schema(description = "Customers counted the same way who visited the studio at least once.",
					example = "171") long everVisited,
			List<LapsedCustomerResponse> customers) {
	}

	@Schema(name = "LapsedCustomer")
	record LapsedCustomerResponse(UUID profileId, CustomerKind kind, String firstName, String lastName,
			@Schema(description = "The participant's own account or, for a child, its guardian's; null when "
					+ "there is none.") @Nullable UUID contactAccountId,
			@Schema(description = "Only for a child: the guardian contacted.") @Nullable String guardianName,
			@Schema(description = "E-mail address of the contact account.") @Nullable String email,
			@Schema(description = "Phone number of the participant or, for a child, its guardian.") @Nullable String phone,
			@Schema(description = "Studio date of the last visit.") LocalDate lastVisit, long daysSinceLastVisit,
			@Schema(description = "Visits in the 90 days up to and including the last one.") int recentVisits,
			@Schema(description = "The pass that tells whether they can still come; null if they never held one.")
			@Nullable PassResponse pass,
			@Schema(description = "Whether the person contacted accepted marketing notifications in the app.")
			boolean marketingConsent,
			@Schema(description = "Studio date the person contacted last got an announcement addressed to them "
					+ "alone.") @Nullable LocalDate lastAnnouncement) {

		static LapsedCustomerResponse from(LapsedCustomer row) {
			var contact = row.contact();
			return new LapsedCustomerResponse(contact.id(), contact.kind(), contact.firstName(), contact.lastName(),
					contact.contactAccountId(), contact.guardianName(), contact.email(), contact.phone(),
					row.lastVisit(), row.daysSinceLastVisit(), row.recentVisits(), PassResponse.from(row.pass()),
					row.marketingConsent(), row.lastAnnouncement());
		}

	}

	@Schema(name = "LapsedCustomerPass")
	record PassResponse(String productName, PassState state, @Nullable LocalDate validUntil,
			@Nullable Integer entriesRemaining) {

		static @Nullable PassResponse from(@Nullable CurrentPass pass) {
			if (pass == null) {
				return null;
			}
			PassState state = !pass.usable() ? PassState.ENDED
					: pass.suspended() ? PassState.SUSPENDED : PassState.VALID;
			return new PassResponse(pass.productName(), state, pass.validUntil(), pass.entriesRemaining());
		}

	}

	@Schema(name = "LapsedCustomerPassState", description = "VALID: usable now or later; SUSPENDED: usable "
			+ "but suspended; ENDED: past its validity or out of entries.")
	enum PassState {

		VALID, SUSPENDED, ENDED

	}

}
