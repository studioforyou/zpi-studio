package studioforyou.pl.backend.reporting.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.shared.api.Money;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/trainer-settlements")
class TrainerSettlementsController {

	private final TrainerSettlements settlements;

	TrainerSettlementsController(TrainerSettlements settlements) {
		this.settlements = settlements;
	}

	@GetMapping
	@Operation(
			summary = "List saved monthly trainer settlements",
			operationId = "listTrainerSettlements")
	List<TrainerSettlements.Summary> list(@RequestParam String month) {
		return settlements.list(month);
	}

	@GetMapping("/export")
	@Operation(
			summary = "Export saved settlements from one consistent database snapshot",
			operationId = "exportTrainerSettlements")
	List<TrainerSettlements.Detail> export(@RequestParam String month) {
		return settlements.export(month);
	}

	@GetMapping("/preview")
	@Operation(
			summary = "Review current hours before approving a settlement",
			operationId = "previewTrainerSettlement")
	TrainerSettlements.Preview preview(@RequestParam String month, @RequestParam UUID staffId) {
		return settlements.preview(month, staffId);
	}

	@GetMapping("/{id}")
	@Operation(
			summary = "Read saved hours, revisions and payout ledger",
			operationId = "getTrainerSettlement")
	TrainerSettlements.Detail detail(@PathVariable UUID id) {
		return settlements.detail(id);
	}

	@GetMapping("/{id}/preview")
	@Operation(
			summary = "Review refreshed hours in the settlement's fixed currency",
			operationId = "previewTrainerSettlementRevision")
	TrainerSettlements.Preview revisionPreview(@PathVariable UUID id) {
		return settlements.revisionPreview(id);
	}

	@PostMapping
	@Operation(
			summary = "Approve a monthly settlement from reviewed hours",
			operationId = "approveTrainerSettlement")
	TrainerSettlements.Detail approve(@Valid @RequestBody Approval request, Authentication auth) {
		return settlements.approve(request, CurrentAccount.of(auth));
	}

	@PostMapping("/{id}/revisions")
	@Operation(
			summary = "Append an explained settlement correction",
			operationId = "reviseTrainerSettlement")
	TrainerSettlements.Detail revise(
			@PathVariable UUID id, @Valid @RequestBody Correction request, Authentication auth) {
		return settlements.revise(id, request, CurrentAccount.of(auth));
	}

	@PostMapping("/{id}/payouts")
	@Operation(
			summary = "Record a manual payout without sending money",
			operationId = "recordTrainerPayout")
	TrainerSettlements.Detail payout(
			@PathVariable UUID id, @Valid @RequestBody Payout request, Authentication auth) {
		return settlements.payout(id, request, CurrentAccount.of(auth));
	}

	@PostMapping("/{id}/payouts/{payoutId}/reversal")
	@Operation(
			summary = "Withdraw an incorrect ledger entry, preserving its history",
			operationId = "reverseTrainerPayout")
	TrainerSettlements.Detail reverse(
			@PathVariable UUID id,
			@PathVariable UUID payoutId,
			@Valid @RequestBody Reversal request,
			Authentication auth) {
		return settlements.reverse(id, payoutId, request, CurrentAccount.of(auth));
	}

	record Approval(
			@NotNull UUID operationId,
			@NotNull UUID staffId,
			@NotBlank String month,
			@JsonDeserialize(using = StrictMoney.class) @NotNull @Valid Money amount,

			boolean includeUnconfirmed,
			@NotBlank String fingerprint,
			@Size(max = 1000) String note) {}

	record Correction(
			@NotNull UUID operationId,
			@JsonDeserialize(using = StrictLong.class) @NotNull @Min(0) Long version,
			@JsonDeserialize(using = StrictMoney.class) @NotNull @Valid Money amount,
			boolean includeUnconfirmed,
			@NotBlank @Size(max = 1000) String reason,
			@Nullable String fingerprint) {}

	enum Method {
		TRANSFER,
		CASH,
		OTHER
	}

	record Payout(
			@NotNull UUID operationId,
			@JsonDeserialize(using = StrictLong.class) @NotNull @Min(0) Long version,
			@JsonDeserialize(using = StrictMoney.class) @NotNull @Valid Money amount,
			@NotNull LocalDate paidOn,
			@NotNull Method method,
			@Size(max = 1000) String note) {}

	record Reversal(
			@NotNull UUID operationId,
			@JsonDeserialize(using = StrictLong.class) @NotNull @Min(0) Long version,
			@NotBlank @Size(max = 1000) String reason) {}

	/** Financial requests must not silently truncate fractional minor units or versions. */
	public static class StrictMoney extends ValueDeserializer<Money> {
		@Override
		public Money deserialize(JsonParser parser, DeserializationContext context) {
			var node = context.readTree(parser);
			if (!node.isObject() || (node.get("amountMinor") != null &&
				(!node.get("amountMinor").isIntegralNumber() || !node.get("amountMinor").canConvertToLong()))) {
				return context.reportInputMismatch(Money.class, "Expected integer minor units.");
			}
			return context.readTreeAsValue(node, Money.class);
		}
	}

	public static class StrictLong extends ValueDeserializer<Long> {
		@Override
		public Long deserialize(JsonParser parser, DeserializationContext context) {
			if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
				return context.reportInputMismatch(Long.class, "Expected a JSON integer.");
			}
			return parser.getLongValue();
		}
	}
}
