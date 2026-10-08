package com.carrental.search.adapter.in.rest.publicapi;

import com.carrental.search.adapter.in.rest.publicapi.query.SearchVehiclesParameters;
import com.carrental.search.adapter.in.rest.publicapi.response.SearchVehiclesResponse;
import com.carrental.search.application.port.in.SearchVehiclesUseCase;
import com.carrental.shared.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API tìm xe công khai theo BR-125/126; không giữ chỗ và không yêu cầu đăng nhập. */
@RestController
@RequestMapping(path = "/api/v1/public/vehicles", produces = MediaType.APPLICATION_JSON_VALUE)
class PublicVehicleSearchController {
    private final SearchVehiclesUseCase search;

    /** Nhận duy nhất cổng tìm kiếm, không biết directory hoặc SQL của module khác. */
    PublicVehicleSearchController(SearchVehiclesUseCase search) {
        this.search = search;
    }

    /** Chuyển tham số HTTP sang query, gọi use case và chỉ trả DTO công khai theo BR-112. */
    @GetMapping
    public ApiResponse<SearchVehiclesResponse> search(@RequestParam MultiValueMap<String, String> parameters) {
        return ApiResponse.success(SearchVehiclesResponse.fromDomain(
                search.search(SearchVehiclesParameters.toQuery(parameters))));
    }
}
