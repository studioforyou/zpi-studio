package studioforyou.pl.backend.offering.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PassProductList")
public record PassProductListResponse(List<PassProductResponse> items) {
}
