package studioforyou.pl.backend.documents.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "OwnDocumentList")
public record OwnDocumentListResponse(List<OwnDocumentResponse> items) {
}
