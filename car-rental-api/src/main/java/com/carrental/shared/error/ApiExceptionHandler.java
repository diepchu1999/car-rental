package com.carrental.shared.error;

import com.carrental.shared.api.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Chuyển exception phát sinh trong Spring MVC thành response lỗi thống nhất.
 *
 * <p>Lỗi có chủ đích sử dụng mã và thông báo từ DomainException.
 * Lỗi request do Spring phát hiện giữ nguyên HTTP và các header cần thiết.
 * Lỗi hệ thống chỉ trả thông báo chung cho phía gọi.
 *
 * <p>Chi tiết lỗi hệ thống được ghi vào log để phục vụ điều tra,
 * không đưa vào nội dung response.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Xử lý lỗi có chủ đích từ domain hoặc application.
     *
     * <p>Nhóm lỗi quyết định HTTP; mã lỗi và thông báo quyết định
     * nội dung công khai trong response.
     *
     * @param exception lỗi cần chuyển thành response
     * @param request ngữ cảnh request hiện tại
     * @return response lỗi, hoặc null nếu response đã được gửi
     */
    @ExceptionHandler(DomainException.class)
    @Nullable ResponseEntity<Object> handleDomainException(
            DomainException exception,
            WebRequest request
    ) {
        HttpStatus status = switch (exception.category()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_CONTENT;
        };

        ApiResponse<Void> body = ApiResponse.failure(
                exception.errorCode().name(),
                exception.getMessage()
        );

        return handleExceptionInternal(
                exception,
                body,
                HttpHeaders.EMPTY,
                status,
                request
        );
    }

    /**
     * Xử lý exception chưa thuộc một cơ chế xử lý cụ thể.
     *
     * <p>Đây là lỗi hệ thống, được trả với HTTP 500 và thông báo chung.
     * Không sử dụng thông báo gốc của exception làm thông báo công khai.
     *
     * @param exception lỗi ngoài dự kiến
     * @param request ngữ cảnh request hiện tại
     * @return response lỗi hệ thống, hoặc null nếu response đã được gửi
     */
    @ExceptionHandler(Exception.class)
    @Nullable ResponseEntity<Object> handleUnexpectedException(
            Exception exception,
            WebRequest request
    ) {
        return handleExceptionInternal(
                exception,
                null,
                HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR,
                request
        );
    }

    /**
     * Chuẩn hóa nội dung response cho lỗi nghiệp vụ và lỗi Spring MVC.
     *
     * <p>Giữ lại ApiResponse đã được handler nghiệp vụ tạo.
     * Những nội dung lỗi khác được thay bằng thông báo công khai mặc định,
     * tránh tiết lộ chi tiết binding, câu SQL hoặc dữ liệu đầu vào.
     *
     * <p>Giữ nguyên HTTP và các header giao thức như Allow.
     * Content-Type được đặt thành JSON; Content-Length cũ được loại bỏ
     * vì nội dung response đã thay đổi.
     *
     * <p>Ủy quyền cho lớp cha kiểm tra response đã được gửi hay chưa,
     * tránh cố ghi lại một response đã hoàn tất.
     *
     * @param exception lỗi đang được xử lý
     * @param body nội dung lỗi được cung cấp; có thể null
     * @param headers các header cần giữ trong response
     * @param statusCode mã HTTP được chọn cho lỗi
     * @param request ngữ cảnh request hiện tại
     * @return response đã chuẩn hóa, hoặc null nếu response đã được gửi
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        if (statusCode.is5xxServerError()) {
            logger.error(
                    "API request failed with status " + statusCode.value() + ".",
                    exception
            );
        }

        ErrorCode fallbackCode = statusCode.is5xxServerError()
                ? ErrorCode.INTERNAL_ERROR
                : ErrorCode.INVALID_REQUEST;

        ApiResponse<?> responseBody = body instanceof ApiResponse<?> apiResponse
                ? apiResponse
                : ApiResponse.failure(
                fallbackCode.name(),
                fallbackCode.defaultMessage()
        );

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.remove(HttpHeaders.CONTENT_LENGTH);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);

        return super.handleExceptionInternal(
                exception,
                responseBody,
                responseHeaders,
                statusCode,
                request
        );
    }
}