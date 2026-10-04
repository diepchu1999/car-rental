package com.carrental.availability.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Kiểm đầu vào chung của bốn command chuyển trạng thái theo hợp đồng mã khóa lịch. */
class ReservationTransitionCommandTest {

    /**
     * Kiểm cả constructor và factory đều chặn mã thiếu, rỗng hoặc trắng với INVALID_REQUEST.
     *
     * @param code mã không hợp lệ
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingCodeInEveryConstructionPath(String code) {
        assertInvalid(() -> new ConfirmReservationCommand(code));
        assertInvalid(() -> ConfirmReservationCommand.from(code));
        assertInvalid(() -> new ReleaseReservationCommand(code));
        assertInvalid(() -> ReleaseReservationCommand.from(code));
        assertInvalid(() -> new MarkReservationInUseCommand(code));
        assertInvalid(() -> MarkReservationInUseCommand.from(code));
        assertInvalid(() -> new CompleteReservationCommand(code));
        assertInvalid(() -> CompleteReservationCommand.from(code));
    }

    /**
     * Kiểm không cắt khoảng trắng hoặc tự áp định dạng mới cho mã có nội dung.
     *
     * @param code mã phải được giữ nguyên
     */
    @ParameterizedTest
    @ValueSource(strings = {"KL-ABC123", "  KL-ABC123  ", "legacy-code"})
    void preservesCodeWithoutNormalization(String code) {
        assertEquals(code, ConfirmReservationCommand.from(code).code());
        assertEquals(code, ReleaseReservationCommand.from(code).code());
        assertEquals(code, MarkReservationInUseCommand.from(code).code());
        assertEquals(code, CompleteReservationCommand.from(code).code());
    }

    /** Kiểm đúng mã lỗi và nhóm lỗi, không chỉ có exception bất kỳ. */
    private static void assertInvalid(Runnable construction) {
        DomainException failure = assertThrows(DomainException.class, construction::run);
        assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode());
        assertEquals(DomainException.Category.INVALID_INPUT, failure.category());
    }
}
