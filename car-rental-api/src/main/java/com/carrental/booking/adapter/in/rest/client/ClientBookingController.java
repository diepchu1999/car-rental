package com.carrental.booking.adapter.in.rest.client;

import com.carrental.booking.adapter.in.rest.client.request.CreateBookingRequest;
import com.carrental.booking.adapter.in.rest.client.response.BookingResponse;
import com.carrental.booking.adapter.in.rest.client.response.SearchBookingsResponse;
import com.carrental.booking.adapter.in.rest.client.query.SearchBookingsParameters;
import com.carrental.booking.application.command.CreateBookingCommand;
import com.carrental.booking.application.command.CancelBookingCommand;
import com.carrental.booking.application.port.in.CancelBookingUseCase;
import com.carrental.booking.application.port.in.CreateBookingUseCase;
import com.carrental.booking.application.port.in.GetBookingUseCase;
import com.carrental.booking.application.port.in.SearchBookingsUseCase;
import com.carrental.booking.application.query.GetBookingQuery;
import com.carrental.shared.api.ApiResponse;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** API khách tạo/đọc/huỷ đơn; quy tắc BR-103/122/131/301/304 nằm trong use case. */
@RestController
@RequestMapping(path = ClientBookingController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class ClientBookingController {
    static final String BASE_PATH = "/api/v1/client/bookings";
    private final CreateBookingUseCase create;
    private final GetBookingUseCase get;
    private final SearchBookingsUseCase search;
    private final CancelBookingUseCase cancel;

    /** Chỉ nhận port/in, không gọi JDBC hoặc module khác ở biên HTTP. */
    ClientBookingController(CreateBookingUseCase create, GetBookingUseCase get, SearchBookingsUseCase search,
            CancelBookingUseCase cancel) {
        this.create = create;
        this.get = get;
        this.search = search;
        this.cancel = cancel;
    }

    /** Tạo hoặc phát lại kết quả ban đầu, cùng 201 và Location theo api-guideline §5. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<BookingResponse>> create(@RequestBody CreateBookingRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        var detail = create.create(CreateBookingCommand.from(request.quoteCode(), key));
        return ResponseEntity.created(URI.create(BASE_PATH + "/" + detail.code()))
                .body(ApiResponse.success(BookingResponse.fromDomain(detail)));
    }

    /** Chỉ chủ đọc được; GET trả trạng thái hiện tại và không tự chuyển trạng thái theo đồng hồ. */
    @GetMapping("/{code}")
    public ApiResponse<BookingResponse> get(@PathVariable("code") String code) {
        return ApiResponse.success(BookingResponse.fromDomain(get.get(GetBookingQuery.from(code))));
    }

    /** BR-301/304: không nhận body hoặc tiền; use case kiểm chủ và huỷ/nhả chỗ nguyên tử. */
    @PostMapping("/{code}/cancel")
    public ApiResponse<BookingResponse> cancel(@PathVariable("code") String code) {
        return ApiResponse.success(BookingResponse.fromDomain(cancel.cancel(CancelBookingCommand.from(code))));
    }

    /** BR-122: trả trang đơn riêng từ snapshot; chủ và vị trí trang được kiểm trong use case. */
    @GetMapping
    public ApiResponse<SearchBookingsResponse> search(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.success(SearchBookingsResponse.fromDomain(
                search.search(SearchBookingsParameters.toQuery(parameters))));
    }
}
