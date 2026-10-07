package com.carrental.branch.adapter.in.internal;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.application.port.in.FindBranchUseCase;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra hợp đồng tra cứu chi nhánh dành cho các module khác.
 *
 * <p>Cổng BranchDirectory cung cấp định danh chi nhánh để module vehicle
 * liên kết xe công ty với chi nhánh đã tồn tại theo BR-003.
 *
 * <p>Test gọi thông qua interface BranchDirectory.
 * Use case được thay bằng lambda có kết quả kiểm soát được.
 *
 * <p>Không khởi động Spring hoặc kết nối cơ sở dữ liệu.
 * Nhóm test này kiểm hành vi Java, không kiểm cơ chế transaction.
 */
class BranchDirectoryTest {

    /**
     * Chứng minh dữ liệu đọc được chuyển thành đúng định danh công khai.
     *
     * <p>Cổng đọc phải được gọi đúng một lần với đúng mã.
     * Kết quả chỉ chứa id và code theo hợp đồng BranchRef.
     */
    @Test
    void returnsReferenceFromUseCase() {
        String code = "CN-READ01";

        BranchDetail storedBranch = new BranchDetail(
                42L,
                code,
                10.762622,
                106.660172, "Test Branch", "123 Test Street"
        );

        List<String> requestedCodes = new ArrayList<>();

        FindBranchUseCase lookup = query -> {
            String requestedCode = query.code();
            requestedCodes.add(requestedCode);
            return Optional.of(storedBranch);
        };

        BranchDirectory directory = directoryWithLookup(lookup);

        Optional<BranchRef> actual = directory.findByCode(code);

        assertEquals(
                Optional.of(new BranchRef(42L, code)),
                actual
        );
        assertEquals(List.of(code), requestedCodes);
    }

    /**
     * Chứng minh không tìm thấy trả kết quả rỗng thay vì ném lỗi.
     *
     * <p>Mã đầu vào được giữ nguyên, kể cả chữ thường
     * hoặc khoảng trắng bao quanh.
     *
     * @param code mã được cổng đọc mô phỏng là không tồn tại
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "CN-MISS01",
            "cn-abc123",
            " CN-ABC123 "
    })
    void returnsEmptyWithoutChangingRequestedCode(String code) {
        List<String> requestedCodes = new ArrayList<>();

        FindBranchUseCase lookup = query -> {
            String requestedCode = query.code();
            requestedCodes.add(requestedCode);
            return Optional.empty();
        };

        BranchDirectory directory = directoryWithLookup(lookup);

        Optional<BranchRef> actual = directory.findByCode(code);

        assertEquals(Optional.empty(), actual);
        assertEquals(List.of(code), requestedCodes);
    }

    /**
     * Chứng minh mã thiếu hoặc trắng bị từ chối trước khi truy cập dữ liệu.
     *
     * <p>Kiểm đồng thời mã lỗi, nhóm lỗi, thông báo
     * và việc cổng đọc chưa được gọi.
     *
     * @param code mã null, rỗng hoặc chỉ chứa ký tự khoảng trắng
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\n"
    })
    void rejectsInvalidCodeBeforeCallingUseCase(String code) {
        List<String> requestedCodes = new ArrayList<>();

        FindBranchUseCase lookup = query -> {
            String requestedCode = query.code();
            requestedCodes.add(requestedCode);
            return Optional.empty();
        };

        BranchDirectory directory = directoryWithLookup(lookup);

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> directory.findByCode(code)
        );

        assertEquals(
                ErrorCode.INVALID_REQUEST,
                failure.errorCode()
        );
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                failure.category()
        );

        String expectedMessage = code == null
                ? "code is required."
                : "code must not be blank.";

        assertEquals(expectedMessage, failure.getMessage());
        assertTrue(
                requestedCodes.isEmpty(),
                "The read port must not be called for invalid input."
        );
    }

    /**
     * Chứng minh lỗi truy cập dữ liệu được truyền ra ngoài nguyên trạng.
     *
     * <p>Cổng tra cứu không biến lỗi lưu trữ thành Optional rỗng
     * và không tự thử lại khi cổng đọc báo lỗi.
     */
    @Test
    void propagatesReadFailureWithoutRetrying() {
        String code = "CN-FAIL01";

        IllegalStateException storageFailure =
                new IllegalStateException(
                        "Simulated storage failure."
                );

        List<String> requestedCodes = new ArrayList<>();

        FindBranchUseCase lookup = query -> {
            String requestedCode = query.code();
            requestedCodes.add(requestedCode);
            throw storageFailure;
        };

        BranchDirectory directory = directoryWithLookup(lookup);

        IllegalStateException actual = assertThrowsExactly(
                IllegalStateException.class,
                () -> directory.findByCode(code)
        );

        assertSame(storageFailure, actual);
        assertEquals(List.of(code), requestedCodes);
    }

    /** Cấp use case tra mã; truy vấn bán kính không được gọi trong những kịch bản này. */
    private static BranchDirectory directoryWithLookup(FindBranchUseCase lookup) {
        return new BranchDirectoryAdapter(lookup, query -> {
            throw new AssertionError("Nearby lookup must not be called during code lookup.");
        });
    }
}
