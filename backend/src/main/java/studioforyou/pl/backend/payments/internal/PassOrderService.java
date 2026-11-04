package studioforyou.pl.backend.payments.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.membership.PassPurchases;
import studioforyou.pl.backend.membership.PassPurchases.PendingPurchase;
import studioforyou.pl.backend.membership.PassPurchases.PurchasedPass;
import studioforyou.pl.backend.offering.PassVariantLookup;
import studioforyou.pl.backend.offering.PassVariantTerms;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Clients buy passes for themselves and for their children. An order holds one pass and is
 * paid in a second step. Until a payment provider is connected the payment is simulated: the
 * client confirms it and it is treated as paid at once ({@link PaymentProvider#MOCK}).
 */
@Service
@Transactional
class PassOrderService {

	private final CustomerOrderRepository orders;

	private final OrderItemRepository items;

	private final PaymentRepository payments;

	private final CustomerProfiles customers;

	private final PassVariantLookup variants;

	private final PassPurchases purchases;

	PassOrderService(CustomerOrderRepository orders, OrderItemRepository items, PaymentRepository payments,
			CustomerProfiles customers, PassVariantLookup variants, PassPurchases purchases) {
		this.orders = orders;
		this.items = items;
		this.payments = payments;
		this.customers = customers;
		this.variants = variants;
		this.purchases = purchases;
	}

	/**
	 * Places an order for one pass for a profile the account acts for. The pass is created
	 * waiting for the payment. A profile the account may not act for is reported as not found,
	 * so its existence is not revealed.
	 */
	PassOrder place(UUID accountId, UUID profileId, NewPassOrder command) {
		if (!customers.isActingFor(accountId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		if (!Boolean.TRUE.equals(command.termsAccepted())) {
			throw ApiException.invalidField("termsAccepted", FieldErrorCode.INVALID_VALUE,
					"The terms of sale must be accepted.");
		}
		if (command.startsOn() == null && command.startAfterPassId() == null) {
			throw ApiException.invalidField("startsOn", FieldErrorCode.REQUIRED,
					"Choose the first day of the pass or the pass it follows.");
		}
		if (command.startsOn() != null && command.startAfterPassId() != null) {
			throw ApiException.invalidField("startAfterPassId", FieldErrorCode.INVALID_VALUE,
					"Give either the first day of the pass or the pass it follows, not both.");
		}
		UUID buyerId = customers.findOwnProfile(accountId)
			.orElseThrow(() -> new IllegalStateException("Account " + accountId + " acts for a profile without one."))
			.id();
		PassVariantTerms terms = variants.findTerms(command.variantId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass product variant not found."));

		CustomerOrder order = orders.save(new CustomerOrder(buyerId, terms.price(), terms.currency(), accountId));
		orders.flush();
		OrderItem item = items
			.save(new OrderItem(order.getId(), terms.variantId(), profileId, terms.productName(), terms.price()));
		items.flush();
		PurchasedPass pass = purchases.createPending(new PendingPurchase(profileId, terms.variantId(),
				command.startsOn(), command.startAfterPassId(), item.getId(), accountId));
		return new PassOrder(order, item, pass);
	}

	/**
	 * Pays the whole order with a simulated payment: the payment is confirmed, the order is
	 * paid and its pass is activated, in one transaction. Only the account that placed the order
	 * pays it; for others the order is not found.
	 */
	PassOrder payWithMock(UUID accountId, UUID orderId) {
		CustomerOrder order = orders.findForUpdate(orderId)
			.filter((found) -> found.getCreatedBy().equals(accountId))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Order not found."));
		if (order.getStatus() != OrderStatus.CREATED) {
			throw new ApiException(ErrorCode.ORDER_NOT_PAYABLE,
					"The order cannot be paid, its status is " + order.getStatus() + ".");
		}
		OrderItem item = singleItem(order);
		Instant now = Instant.now();
		payments.save(Payment.confirmedMock(order, now));
		order.markPaid(now);
		PurchasedPass pass = purchases.activate(item.getId(), accountId);
		orders.flush();
		return new PassOrder(order, item, pass);
	}

	private OrderItem singleItem(CustomerOrder order) {
		List<OrderItem> orderItems = items.findByOrderId(order.getId());
		if (orderItems.size() != 1) {
			throw new IllegalStateException("Order " + order.getId() + " has " + orderItems.size() + " items.");
		}
		return orderItems.get(0);
	}

	/**
	 * @param startsOn the chosen first day of the pass, or {@code null} with {@code startAfterPassId}
	 * @param startAfterPassId a pass of the participant the new one starts after, or {@code null}
	 * with {@code startsOn}
	 * @param termsAccepted whether the client accepted the terms of sale; must be {@code true}
	 */
	record NewPassOrder(UUID variantId, LocalDate startsOn, UUID startAfterPassId, Boolean termsAccepted) {
	}

	/**
	 * An order with its one line and the pass it buys.
	 */
	record PassOrder(CustomerOrder order, OrderItem item, PurchasedPass pass) {
	}

}
