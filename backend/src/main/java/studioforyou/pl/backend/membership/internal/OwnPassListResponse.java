package studioforyou.pl.backend.membership.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OwnPassList")
public record OwnPassListResponse(List<OwnPassResponse> items) {
}
