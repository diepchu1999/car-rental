package com.carrental.vehicle.application.command;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.domain.DailyRate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm hợp đồng command đổi giá BR-234, không phụ thuộc DTO HTTP. */
class ChangeVehicleDailyRateCommandTest {
    /** Factory giữ nguyên mã và số tiền đã nhập, không tự chuẩn hóa mã. */
    @Test
    void preservesCodeAndRate() {
        var command = ChangeVehicleDailyRateCommand.from(" XE-ABC123 ", new BigDecimal("855000"));
        assertEquals(" XE-ABC123 ", command.code());
        assertEquals(new DailyRate(855_000), command.dailyRate());
    }

    /** Cả constructor lẫn factory đều không cho biến null thành thao tác xóa giá. */
    @Test
    void rejectsMissingRate() {
        assertEquals(ErrorCode.VEHICLE_INVALID_DAILY_RATE, assertThrows(DomainException.class,
                () -> new ChangeVehicleDailyRateCommand("XE-ABC123", null)).errorCode());
        assertEquals(ErrorCode.VEHICLE_INVALID_DAILY_RATE, assertThrows(DomainException.class,
                () -> ChangeVehicleDailyRateCommand.from("XE-ABC123", null)).errorCode());
    }

    /** Mã thiếu hoặc trắng bị từ chối trước khi gọi use case. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankCode(String code) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(DomainException.class,
                () -> ChangeVehicleDailyRateCommand.from(code, new BigDecimal("1000"))).errorCode());
    }
}
