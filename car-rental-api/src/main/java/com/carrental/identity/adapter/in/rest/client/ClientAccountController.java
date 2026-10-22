package com.carrental.identity.adapter.in.rest.client;

import com.carrental.identity.adapter.in.rest.response.CurrentAccountResponse;
import com.carrental.identity.application.port.in.GetCurrentAccountUseCase;
import com.carrental.shared.api.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BR-120/809: đọc tài khoản của khách đã xác thực; Security yêu cầu vai CUSTOMER cho namespace client. */
@RestController
@RequestMapping(path = ClientAccountController.BASE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
class ClientAccountController {
    static final String BASE_PATH = "/api/v1/client/accounts";
    private final GetCurrentAccountUseCase accounts;

    /** Chỉ nhận port/in, không đọc token, CallerDirectory hoặc JDBC trong controller. */
    ClientAccountController(GetCurrentAccountUseCase accounts) { this.accounts = accounts; }

    /** Không nhận mã tài khoản do client khai; use case đọc đúng danh tính đã xác thực của request. */
    @GetMapping("/me")
    public ApiResponse<CurrentAccountResponse> getCurrent() {
        return ApiResponse.success(CurrentAccountResponse.fromDomain(accounts.get()));
    }
}
