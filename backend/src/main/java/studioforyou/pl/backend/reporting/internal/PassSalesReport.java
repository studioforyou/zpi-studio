package studioforyou.pl.backend.reporting.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.membership.PassSales;
import studioforyou.pl.backend.membership.PassSales.SoldPass;
import studioforyou.pl.backend.offering.PassProductKind;
import studioforyou.pl.backend.offering.PassVariantLookup;
import studioforyou.pl.backend.offering.PassVariantTerms;

/**
 * Passes sold in a period, by kind of pass, product and price variant.
 * <ul>
 * <li>A pass counts as <em>sold</em> when it becomes the participant's: when its order is paid, or
 * when the studio staff assign it. Passes waiting for the payment and cancelled ones do not count.</li>
 * <li><em>Revenue</em> is the price actually paid; a pass given for free counts as sold for zero and
 * is also counted in {@code free}.</li>
 * <li>A pass bought by the client counts as sold {@code online}, one the staff assigned as sold in
 * the {@code studio}.</li>
 * </ul>
 * The same totals are given for the period of the same length right before, for comparison.
 */
@Service
class PassSalesReport {

	private final StudioSettings studioSettings;

	private final PassSales passSales;

	private final PassVariantLookup variants;

	PassSalesReport(StudioSettings studioSettings, PassSales passSales, PassVariantLookup variants) {
		this.studioSettings = studioSettings;
		this.passSales = passSales;
		this.variants = variants;
	}

	Report build(ReportPeriod period) {
		ZoneId zone = this.studioSettings.zone();
		long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
		ReportPeriod before = new ReportPeriod(period.from().minusDays(days), period.from().minusDays(1));
		List<SoldPass> sold = this.passSales.findSoldBetween(period.start(zone), period.end(zone));
		List<SoldPass> soldBefore = this.passSales.findSoldBetween(before.start(zone), before.end(zone));
		Map<UUID, PassVariantTerms> terms = this.variants
			.findAllTerms(sold.stream().map(SoldPass::variantId).collect(Collectors.toSet()));

		Tally totals = new Tally();
		Tally previous = new Tally();
		soldBefore.forEach(previous::add);
		Map<PassProductKind, Tally> kinds = new EnumMap<>(PassProductKind.class);
		Arrays.stream(PassProductKind.values()).forEach((kind) -> kinds.put(kind, new Tally()));
		Map<UUID, ProductTally> products = new LinkedHashMap<>();
		Map<LocalDate, Map<PassProductKind, Tally>> perDay = new TreeMap<>();
		for (SoldPass pass : sold) {
			PassVariantTerms variant = terms.get(pass.variantId());
			totals.add(pass);
			kinds.get(variant.kind()).add(pass);
			products.computeIfAbsent(variant.productId(), (id) -> new ProductTally(variant)).add(pass, variant);
			perDay.computeIfAbsent(pass.soldAt().atZone(zone).toLocalDate(), (date) -> new EnumMap<>(PassProductKind.class))
				.computeIfAbsent(variant.kind(), (kind) -> new Tally())
				.add(pass);
		}
		List<ProductRow> productRows = products.values()
			.stream()
			.map(ProductTally::row)
			.sorted(Comparator.comparingLong((ProductRow row) -> -row.counts().passes())
				.thenComparingLong((row) -> -row.counts().revenueMinor())
				.thenComparing(ProductRow::name)
				.thenComparing(ProductRow::productId))
			.toList();
		List<DayRow> dayRows = perDay.entrySet()
			.stream()
			.flatMap((day) -> day.getValue()
				.entrySet()
				.stream()
				.map((kind) -> new DayRow(day.getKey(), kind.getKey(), kind.getValue().counts())))
			.toList();
		return new Report(period.from(), period.to(), this.studioSettings.currency(), totals.counts(),
				new PreviousPeriod(before.from(), before.to(), previous.counts()),
				kinds.entrySet().stream().map((kind) -> new KindRow(kind.getKey(), kind.getValue().counts())).toList(),
				productRows, dayRows);
	}

	private static long minor(BigDecimal amount) {
		return amount.movePointRight(2).longValueExact();
	}

	@Schema(name = "PassSalesReport")
	record Report(@Schema(example = "2026-10-01") LocalDate from, @Schema(example = "2026-10-31") LocalDate to,
			@Schema(description = "Currency of every amount, in minor units.", example = "PLN") String currency,
			SaleCounts totals,
			@Schema(description = "The period of the same length right before the requested one.") PreviousPeriod previous,
			@Schema(description = "Every kind of pass, in a fixed order, also those not sold.") List<KindRow> kinds,
			@Schema(description = "Products sold in the period, the most passes first.") List<ProductRow> products,
			@Schema(description = "Studio dates with sales, per kind of pass.") List<DayRow> days) {
	}

	@Schema(name = "PassSaleCounts")
	record SaleCounts(@Schema(description = "Passes sold or assigned.", example = "42") long passes,
			@Schema(description = "Prices actually paid, in minor units.", example = "1086000") long revenueMinor,
			@Schema(description = "Passes given for free, counted in passes too.", example = "1") long free,
			@Schema(description = "Passes clients bought themselves.", example = "35") long online,
			@Schema(description = "Passes the studio staff assigned.", example = "7") long studio) {
	}

	@Schema(name = "PassSalesPreviousPeriod")
	record PreviousPeriod(LocalDate from, LocalDate to, SaleCounts totals) {
	}

	@Schema(name = "PassSalesKind")
	record KindRow(PassProductKind kind, SaleCounts counts) {
	}

	@Schema(name = "PassSalesProduct")
	record ProductRow(UUID productId, String name, PassProductKind kind, SaleCounts counts,
			@Schema(description = "Price variants sold, the most passes first.") List<VariantRow> variants) {
	}

	@Schema(name = "PassSalesVariant")
	record VariantRow(UUID variantId,
			@Schema(description = "Number of entries; null for unlimited entries.") @Nullable Short entriesCount,
			short validityDays, @Schema(description = "The variant's list price, in minor units.") long priceMinor,
			SaleCounts counts) {
	}

	@Schema(name = "PassSalesDay")
	record DayRow(LocalDate date, PassProductKind kind, SaleCounts counts) {
	}

	private static class Tally {

		private long passes;

		private long revenueMinor;

		private long free;

		private long online;

		void add(SoldPass pass) {
			this.passes++;
			this.revenueMinor += minor(pass.pricePaid());
			if (pass.pricePaid().signum() == 0) {
				this.free++;
			}
			if (pass.ordered()) {
				this.online++;
			}
		}

		SaleCounts counts() {
			return new SaleCounts(this.passes, this.revenueMinor, this.free, this.online, this.passes - this.online);
		}

	}

	private static final class ProductTally extends Tally {

		private final PassVariantTerms product;

		private final Map<UUID, Tally> variants = new LinkedHashMap<>();

		private final Map<UUID, PassVariantTerms> variantTerms = new LinkedHashMap<>();

		ProductTally(PassVariantTerms product) {
			this.product = product;
		}

		void add(SoldPass pass, PassVariantTerms variant) {
			add(pass);
			this.variantTerms.putIfAbsent(variant.variantId(), variant);
			this.variants.computeIfAbsent(variant.variantId(), (id) -> new Tally()).add(pass);
		}

		ProductRow row() {
			List<VariantRow> rows = this.variants.entrySet().stream().map((entry) -> {
				PassVariantTerms terms = this.variantTerms.get(entry.getKey());
				return new VariantRow(terms.variantId(), terms.entriesCount(), terms.validityDays(),
						minor(terms.price()), entry.getValue().counts());
			})
				.sorted(Comparator.comparingLong((VariantRow row) -> -row.counts().passes())
					.thenComparingLong(VariantRow::priceMinor)
					.thenComparing(VariantRow::variantId))
				.toList();
			return new ProductRow(this.product.productId(), this.product.productName(), this.product.kind(), counts(),
					rows);
		}

	}

}
