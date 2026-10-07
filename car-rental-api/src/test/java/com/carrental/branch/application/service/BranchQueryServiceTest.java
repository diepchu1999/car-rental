package com.carrental.branch.application.service;

import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.BranchReadPortStub;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm tra hành vi điều phối của service đọc chi nhánh.
 *
 * <p>Thông tin vị trí phục vụ BR-003. Trường hợp không tìm thấy
 * sử dụng BRANCH_NOT_FOUND theo backend-guideline mục 5.
 *
 * <p>Cổng đọc được thay bằng stub bọc lambda có kết quả kiểm soát được.
 * Test không khởi động Spring, không kết nối cơ sở dữ liệu
 * và không kiểm chứng cơ chế transaction.
 */
class BranchQueryServiceTest {

    /**
     * Chứng minh service gọi cổng đọc đúng một lần với đúng mã,
     * rồi trả nguyên view nhận được từ cổng đọc.
     */
    @Test
    void returnsDetailFromReadPort() {
        String code = "CN-READ01";

        BranchDetail expected = new BranchDetail(
                42L,
                code,
                10.762622,
                106.660172, "Test Branch", "123 Test Street"
        );

        List<String> requestedCodes = new ArrayList<>();

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(requestedCode -> {
            requestedCodes.add(requestedCode);
            return Optional.of(expected);
        });

        BranchQueryService service = new BranchQueryService(
                readBranchPort
        );

        BranchDetail actual = service.get(
                GetBranchQuery.from(code)
        );

        assertSame(expected, actual);
        assertEquals(List.of(code), requestedCodes);
    }

    /**
     * Chứng minh kết quả rỗng được chuyển thành đúng lỗi không tìm thấy.
     *
     * <p>Service phải giữ nguyên mã, kể cả chữ thường hoặc khoảng trắng
     * bao quanh. Query chỉ yêu cầu đầu vào không thiếu hoặc trắng,
     * không áp lại quy tắc định dạng mã khi tạo chi nhánh.
     *
     * @param code mã được cổng đọc mô phỏng là không tồn tại
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "CN-MISS01",
            "cn-abc123",
            " CN-ABC123 "
    })
    void reportsNotFoundWithoutChangingRequestedCode(String code) {
        List<String> requestedCodes = new ArrayList<>();

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(requestedCode -> {
            requestedCodes.add(requestedCode);
            return Optional.empty();
        });

        BranchQueryService service = new BranchQueryService(
                readBranchPort
        );

        GetBranchQuery query = GetBranchQuery.from(code);

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> service.get(query)
        );

        assertEquals(
                ErrorCode.BRANCH_NOT_FOUND,
                failure.errorCode()
        );

        assertEquals(
                DomainException.Category.NOT_FOUND,
                failure.category()
        );

        assertEquals(
                ErrorCode.BRANCH_NOT_FOUND.defaultMessage(),
                failure.getMessage()
        );

        assertEquals(List.of(code), requestedCodes);
    }

    /**
     * Chứng minh lỗi cổng đọc được truyền ra ngoài nguyên trạng.
     *
     * <p>Service không biến lỗi lưu trữ thành lỗi không tìm thấy
     * và không tự gọi lại cổng đọc khi có lỗi.
     */
    @Test
    void propagatesReadFailureWithoutRetrying() {
        String code = "CN-FAIL01";

        IllegalStateException storageFailure =
                new IllegalStateException(
                        "Simulated storage failure."
                );

        List<String> requestedCodes = new ArrayList<>();

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(requestedCode -> {
            requestedCodes.add(requestedCode);
            throw storageFailure;
        });

        BranchQueryService service = new BranchQueryService(
                readBranchPort
        );

        GetBranchQuery query = GetBranchQuery.from(code);

        IllegalStateException actual = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.get(query)
        );

        assertSame(storageFailure, actual);
        assertEquals(List.of(code), requestedCodes);
    }
}
