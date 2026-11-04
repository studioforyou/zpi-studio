package studioforyou.pl.backend.payments.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.shared.api.Money;

/**
 * An order for one pass, with the pass it buys.
 *
 * @param status {@code CREATED} while waiting for the payment, {@code PAID} once paid
 * @param customerId the participant the pass is for
 * @param productName the product's name at the time of the order
 * @param price the price at the time of the order, the order's total
 * @param paidAt when the order was paid, or {@code null}
 * @param pass the pass bought with the order
 */
@Schema(name = "PassOrder")
public record PassOrderResponse(UUID id, @Schema(example = "PAID") String status, UUID customerId, UUID variantId,
		@Schema(example = "Karnet Pole Dance") String productName, Money price, Instant createdAt, Instant paidAt,
		OrderedPass pass) {

	static PassOrderResponse from(PassOrderService.PassOrder order) {
		CustomerOrder header = order.order();
		OrderItem item = order.item();
		return new PassOrderResponse(header.getId(), header.getStatus().name(), item.getBeneficiaryId(),
				item.getVariantId(), item.getProductName(), Money.of(header.getTotalAmount(), header.getCurrency()),
				header.getCreatedAt(), header.getPaidAt(), new OrderedPass(order.pass().passId(),
						order.pass().status(), order.pass().validFrom(), order.pass().validUntil()));
	}

	/**
	 * @param status {@code PENDING_PAYMENT} until the order is paid, then {@code SCHEDULED} or
	 * {@code ACTIVE}
	 * @param validUntil the last day the pass is valid, inclusive
	 */
	@Schema(name = "OrderedPass")
	public record OrderedPass(UUID id, CustomerPassStatus status, LocalDate validFrom, LocalDate validUntil) {
	}

}
