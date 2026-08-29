package com.carrental.branch.domain;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Kiểm tra tính hợp lệ và việc giữ nguyên tọa độ chi nhánh.
 *
 * <p>Đối tượng vị trí phục vụ BR-003. Test chỉ kiểm domain,
 * không khởi động Spring hoặc truy cập PostGIS.
 *
 * <p>Cả constructor trực tiếp và factory phải bảo vệ cùng
 * giới hạn tọa độ địa lý.
 */
class BranchLocationTest {

    /**
     * Kiểm tọa độ thông thường, tọa độ bằng 0 và các giá trị biên
     * được chấp nhận, không bị đảo trục hoặc làm tròn.
     *
     * @param latitude vĩ độ hợp lệ
     * @param longitude kinh độ hợp lệ
     */
    @ParameterizedTest
    @CsvSource({
            "10.762622, 106.660172",
            "0.0, 0.0",
            "-90.0, -180.0",
            "90.0, 180.0",
            "-33.8688, 151.2093"
    })
    void preservesValidCoordinates(double latitude, double longitude) {
        BranchLocation fromConstructor = new BranchLocation(
                latitude,
                longitude
        );
        BranchLocation fromFactory = BranchLocation.from(
                latitude,
                longitude
        );

        assertEquals(latitude, fromConstructor.latitude());
        assertEquals(longitude, fromConstructor.longitude());
        assertEquals(latitude, fromFactory.latitude());
        assertEquals(longitude, fromFactory.longitude());
    }

    /**
     * Cung cấp vĩ độ ngay bên ngoài hai giới hạn và các giá trị
     * không phải số hữu hạn.
     *
     * @return các vĩ độ không hợp lệ
     */
    private static Stream<Double> invalidLatitudes() {
        return Stream.of(
                Math.nextDown(-90.0),
                Math.nextUp(90.0),
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY
        );
    }

    /**
     * Kiểm vĩ độ sai bị từ chối ở cả constructor và factory.
     *
     * <p>Kinh độ được giữ hợp lệ để lỗi chỉ phát sinh từ vĩ độ.
     *
     * @param latitude vĩ độ không hợp lệ
     */
    @ParameterizedTest
    @MethodSource("invalidLatitudes")
    void rejectsInvalidLatitudes(double latitude) {
        String expectedMessage =
                "latitude must be a finite number between -90 and 90.";

        assertInvalidInput(
                () -> new BranchLocation(latitude, 0.0),
                expectedMessage
        );

        assertInvalidInput(
                () -> BranchLocation.from(latitude, 0.0),
                expectedMessage
        );
    }

    /**
     * Cung cấp kinh độ ngay bên ngoài hai giới hạn và các giá trị
     * không phải số hữu hạn.
     *
     * @return các kinh độ không hợp lệ
     */
    private static Stream<Double> invalidLongitudes() {
        return Stream.of(
                Math.nextDown(-180.0),
                Math.nextUp(180.0),
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY
        );
    }

    /**
     * Kiểm kinh độ sai bị từ chối ở cả constructor và factory.
     *
     * <p>Vĩ độ được giữ hợp lệ để lỗi chỉ phát sinh từ kinh độ.
     *
     * @param longitude kinh độ không hợp lệ
     */
    @ParameterizedTest
    @MethodSource("invalidLongitudes")
    void rejectsInvalidLongitudes(double longitude) {
        String expectedMessage =
                "longitude must be a finite number between -180 and 180.";

        assertInvalidInput(
                () -> new BranchLocation(0.0, longitude),
                expectedMessage
        );

        assertInvalidInput(
                () -> BranchLocation.from(0.0, longitude),
                expectedMessage
        );
    }

    /**
     * Cung cấp các trường hợp thiếu một hoặc cả hai tọa độ.
     *
     * <p>Khi thiếu cả hai, factory kiểm vĩ độ trước nên báo
     * thiếu vĩ độ trước.
     *
     * @return tọa độ đầu vào và thông báo lỗi mong đợi
     */
    private static Stream<Arguments> missingCoordinates() {
        return Stream.of(
                Arguments.of(
                        null,
                        0.0,
                        "latitude is required."
                ),
                Arguments.of(
                        0.0,
                        null,
                        "longitude is required."
                ),
                Arguments.of(
                        null,
                        null,
                        "latitude is required."
                )
        );
    }

    /**
     * Kiểm tọa độ thiếu được báo bằng lỗi đầu vào có chủ đích,
     * không bị thay bằng 0 hoặc gây NullPointerException.
     *
     * @param latitude vĩ độ đầu vào, có thể null
     * @param longitude kinh độ đầu vào, có thể null
     * @param expectedMessage thông báo chỉ rõ tọa độ bị thiếu
     */
    @ParameterizedTest
    @MethodSource("missingCoordinates")
    void rejectsMissingCoordinates(
            Double latitude,
            Double longitude,
            String expectedMessage
    ) {
        assertInvalidInput(
                () -> BranchLocation.from(latitude, longitude),
                expectedMessage
        );
    }

    /**
     * Kiểm lời gọi bị từ chối với đúng loại exception,
     * mã lỗi, nhóm lỗi và thông báo công khai.
     *
     * @param action thao tác tạo tọa độ không hợp lệ
     * @param expectedMessage thông báo lỗi mong đợi
     */
    private static void assertInvalidInput(
            Executable action,
            String expectedMessage
    ) {
        DomainException exception = assertThrows(
                DomainException.class,
                action
        );

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode());
        assertEquals(
                DomainException.Category.INVALID_INPUT,
                exception.category()
        );
        assertEquals(expectedMessage, exception.getMessage());
    }
}