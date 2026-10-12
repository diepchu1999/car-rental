package com.carrental.pricing.adapter.in.rest.client;

import com.carrental.pricing.adapter.in.rest.client.request.CreateQuoteRequest;
import com.carrental.pricing.adapter.in.rest.client.response.QuoteResponse;
import com.carrental.pricing.application.command.CreateQuoteCommand;
import com.carrental.pricing.application.port.in.CreateQuoteUseCase;
import com.carrental.pricing.application.port.in.GetQuoteUseCase;
import com.carrental.pricing.application.query.GetQuoteQuery;
import com.carrental.shared.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** API báo giá BR-122; namespace client chưa có xác thực, identity sẽ bổ sung ở lát cắt sau. */
@RestController
@RequestMapping(path = ClientQuoteController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class ClientQuoteController {
    static final String BASE_PATH = "/api/v1/client/quotes";
    private final CreateQuoteUseCase create;
    private final GetQuoteUseCase get;

    /** Nhận port/in, không gọi module khác hoặc JDBC tại controller. */
    ClientQuoteController(CreateQuoteUseCase create, GetQuoteUseCase get) { this.create = create; this.get = get; }

    /** Tạo tài nguyên, trả 201 và Location theo mã báo giá; giá/hạn do application quyết định. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<QuoteResponse>> create(@RequestBody CreateQuoteRequest request) {
        var detail = create.create(CreateQuoteCommand.from(request.vehicleCode(), request.startAt(), request.endAt(),
                request.rentalType(), request.driveMode(), request.pickupMethod()));
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + detail.code()))
                .body(ApiResponse.success(QuoteResponse.fromDomain(detail)));
    }

    /** Đọc snapshot, kể cả hết hạn; không kiểm QUOTE_EXPIRED ở endpoint này. */
    @GetMapping("/{code}")
    public ApiResponse<QuoteResponse> get(@PathVariable("code") String code) {
        return ApiResponse.success(QuoteResponse.fromDomain(get.get(GetQuoteQuery.from(code))));
    }
}
