package studioforyou.pl.backend.offering.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import studioforyou.pl.backend.shared.api.Money;

@Schema(name = "ChangeVariantPriceRequest")
public record ChangeVariantPriceRequest(@NotNull @Valid Money price) {
}
