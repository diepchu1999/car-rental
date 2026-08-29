package com.carrental.vehicle.application;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Kiểm hợp đồng mã xe của đầu vào đọc, gửi duyệt và phê duyệt.
 *
 * <p>Các thao tác gửi duyệt và phê duyệt phục vụ BR-010.
 * Nhóm test này chỉ kiểm cấu trúc đầu vào, không kiểm trạng thái xe.
 *
 * <p>Cả factory và constructor phải từ chối mã thiếu hoặc trắng,
 * đồng thời giữ nguyên mọi mã có nội dung.
 *
 * <p>Test chạy Java thuần, không dùng Spring hoặc database.
 */
class VehicleCodeInputsTest {

    /**
     * Chứng minh các đầu vào giữ nguyên mã có nội dung.
     *
     * <p>Không tự chuyển chữ thường thành chữ hoa, cắt khoảng trắng
     * hoặc áp quy tắc định dạng mã lúc tạo xe vào thao tác tra cứu.
     *
     * <p>So sánh thêm với constructor trực tiếp để kiểm
     * hai đường khởi tạo cho cùng kết quả.
     *
     * @param code mã có nội dung cần được giữ nguyên
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "XE-ABC123",
            "xe-abc123",
            " XE-ABC123 ",
            "missing-vehicle"
    })
    void preservesNonBlankCodesAcrossAllInputs(String code) {
        GetVehicleQuery query = GetVehicleQuery.from(code);

        SubmitVehicleForApprovalCommand submitCommand =
                SubmitVehicleForApprovalCommand.from(code);

        ApproveVehicleCommand approveCommand =
                ApproveVehicleCommand.from(code);

        assertEquals(code, query.code());
        assertEquals(code, submitCommand.code());
        assertEquals(code, approveCommand.code());

        assertEquals(
                new GetVehicleQuery(code),
                query
        );
        assertEquals(
                new SubmitVehicleForApprovalCommand(code),
                submitCommand
        );
        assertEquals(
                new ApproveVehicleCommand(code),
                approveCommand
        );
    }

    /**
     * Chứng minh mã thiếu hoặc trắng bị từ chối ở mọi đường khởi tạo.
     *
     * <p>Kiểm ba factory và ba constructor.
     * assertAll cho phép báo các đường thất bại trong cùng một lượt test.
     *
     * @param code mã null, rỗng hoặc chỉ chứa khoảng trắng
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\n"
    })
    void rejectsMissingOrBlankCodesAcrossAllInputs(String code) {
        String expectedMessage = code == null
                ? "code is required."
                : "code must not be blank.";

        assertAll(
                "Every input creation path must reject invalid codes.",
                () -> assertInvalidInput(
                        () -> GetVehicleQuery.from(code),
                        expectedMessage
                ),
                () -> assertInvalidInput(
                        () -> new GetVehicleQuery(code),
                        expectedMessage
                ),
                () -> assertInvalidInput(
                        () -> SubmitVehicleForApprovalCommand.from(code),
                        expectedMessage
                ),
                () -> assertInvalidInput(
                        () -> new SubmitVehicleForApprovalCommand(code),
                        expectedMessage
                ),
                () -> assertInvalidInput(
                        () -> ApproveVehicleCommand.from(code),
                        expectedMessage
                ),
                () -> assertInvalidInput(
                        () -> new ApproveVehicleCommand(code),
                        expectedMessage
                )
        );
    }

    /**
     * Kiểm thao tác bị từ chối với đúng exception và thông tin lỗi đầu vào.
     *
     * @param action thao tác tạo query hoặc command cần kiểm
     * @param expectedMessage thông báo lỗi mong đợi
     */
    private static void assertInvalidInput(
            Executable action,
            String expectedMessage
    ) {
        DomainException failure = assertThrowsExactly(
                DomainException.class,
                action
        );

        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                failure.category()
        );
        assertEquals(expectedMessage, failure.getMessage());
    }
}