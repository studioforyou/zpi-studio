package studioforyou.pl.backend.offering.web;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ServiceTypeList")
public record ServiceTypeListResponse(List<ServiceTypeResponse> items) {
}
