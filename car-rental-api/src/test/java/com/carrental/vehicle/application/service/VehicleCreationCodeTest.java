package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.api.BranchSearchView;
import com.carrental.shared.code.BusinessCodeGeneratorTestFactory;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra sinh mã và thử lại trong luồng tạo xe bản nháp.
 *
 * <p>Liên kết chi nhánh tuân theo BR-003. Điều kiện giấy tờ
 * của BR-005 không được áp dụng sớm vào bước tạo DRAFT.
 *
 * <p>Bộ sinh mã thật nhận nguồn số kiểm soát được.
 * Các port được mô phỏng; test không khởi động Spring,
 * không kết nối database và không kiểm chứng rollback.
 */
class VehicleCreationCodeTest {

    private static final BranchRef BRANCH =
            new BranchRef(42L, "CN-BRAN01");

    private static final CreateVehicleCommand COMMAND =
            CreateVehicleCommand.from(
                    "51H-123.45",
                    OwnershipType.COMPANY,
                    FuelType.PETROL,
                    BRANCH.code(),
                    null,
                    null,
                    SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                    SPECIFICATIONS.make(), SPECIFICATIONS.model()
            );

    private static final List<String> GENERATED_CODES = List.of(
            "XE-AAAAAA",
            "XE-BBBBBB",
            "XE-CCCCCC",
            "XE-DDDDDD",
            "XE-EEEEEE"
    );

    /**
     * Chứng minh tạo thành công ngay hoặc sau các lần trùng mã.
     *
     * <p>Chi nhánh chỉ được tra cứu một lần trước khi chèn.
     * Mỗi lần thử giữ nguyên dữ liệu và trạng thái DRAFT.
     * Chỉ đọc lại sau khi chèn thành công.
     *
     * <p>Bốn lần trùng chứng minh lần thử thứ năm vẫn được thực hiện.
     * Giấy tờ chưa có vẫn không ngăn tạo bản nháp theo BR-005.
     *
     * @param collisionsBeforeSuccess số lần trùng mã trước khi thành công
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void retriesCodeCollisionsAndReturnsReloadedDetail(
            int collisionsBeforeSuccess
    ) {
        int attemptCount = collisionsBeforeSuccess + 1;
        String successfulCode = GENERATED_CODES.get(attemptCount - 1);

        SequentialRandom random = new SequentialRandom(attemptCount);
        VehicleDetail expected = detail(successfulCode);

        List<String> operations = new ArrayList<>();
        List<Vehicle> insertedVehicles = new ArrayList<>();

        BranchDirectory branchDirectory = new BranchDirectory() {
            /** Giữ nguyên hành vi tra mã của fixture tạo xe. */
            @Override
            public Optional<BranchRef> findByCode(String code) {
                operations.add("branch:" + code);
                return Optional.of(BRANCH);
            }

            /** Tạo xe tái sử dụng mã đã tra trước khi chèn, không tra ID lần nữa. */
            @Override
            public Optional<BranchRef> findById(long id) {
                throw new AssertionError("ID lookup must not be called during vehicle creation.");
            }

            /** Luồng tạo xe không được gọi truy vấn bán kính. */
            @Override
            public List<BranchSearchView> findWithinRadius(
                    Double latitude, Double longitude, Double radiusMeters) {
                throw new AssertionError("Radius lookup must not be called during vehicle creation.");
            }
        };

        WriteVehiclePort writeVehiclePort = new InsertOnlyWritePort(
                vehicle -> {
                    operations.add("insert:" + vehicle.code());
                    insertedVehicles.add(vehicle);
                    return insertedVehicles.size() == attemptCount;
                }
        );

        ReadVehiclePort readVehiclePort = new ViewOnlyVehicleReadPort(
                code -> {
                    operations.add("read:" + code);
                    return Optional.of(expected);
                }
        );

        VehicleCommandService service = new VehicleCommandService(
                writeVehiclePort,
                readVehiclePort,
                BusinessCodeGeneratorTestFactory.create(random),
                branchDirectory,
                Clock.systemUTC()
        );

        VehicleDetail actual = service.create(COMMAND);

        assertEquals(expected.withBranchCode(BRANCH.code()), actual);
        assertEquals(attemptCount, insertedVehicles.size());

        List<String> expectedOperations = new ArrayList<>();
        expectedOperations.add("branch:" + BRANCH.code());

        for (int index = 0; index < attemptCount; index++) {
            String expectedCode = GENERATED_CODES.get(index);
            Vehicle vehicle = insertedVehicles.get(index);

            assertEquals(expectedCode, vehicle.code());
            assertEquals(COMMAND.plateNumber(), vehicle.plateNumber());
            assertEquals(COMMAND.ownershipType(), vehicle.ownershipType());
            assertEquals(COMMAND.fuelType(), vehicle.fuelType());
            assertEquals(Long.valueOf(BRANCH.id()), vehicle.branchId());
            assertEquals(VehicleStatus.DRAFT, vehicle.status());
            assertEquals(COMMAND.documents(), vehicle.documents());

            expectedOperations.add("insert:" + expectedCode);
        }

        expectedOperations.add("read:" + successfulCode);

        assertEquals(expectedOperations, operations);
        random.assertExhausted();
    }

    /**
     * Chứng minh service dừng sau đúng năm lần chèn bị trùng mã.
     *
     * <p>Không đọc lại vì chưa chèn được xe nào.
     * Hết lượt thử là lỗi nội bộ, không phải lỗi không tìm thấy xe.
     */
    @Test
    void stopsAfterFiveCodeCollisionsWithoutReading() {
        SequentialRandom random = new SequentialRandom(5);
        List<String> operations = new ArrayList<>();

        BranchDirectory branchDirectory = new BranchDirectory() {
            /** Giữ nguyên hành vi tra mã của fixture tạo xe. */
            @Override
            public Optional<BranchRef> findByCode(String code) {
                operations.add("branch:" + code);
                return Optional.of(BRANCH);
            }

            /** Tạo xe tái sử dụng mã đã tra trước khi chèn, không tra ID lần nữa. */
            @Override
            public Optional<BranchRef> findById(long id) {
                throw new AssertionError("ID lookup must not be called during vehicle creation.");
            }

            /** Luồng tạo xe không được gọi truy vấn bán kính. */
            @Override
            public List<BranchSearchView> findWithinRadius(
                    Double latitude, Double longitude, Double radiusMeters) {
                throw new AssertionError("Radius lookup must not be called during vehicle creation.");
            }
        };

        WriteVehiclePort writeVehiclePort = new InsertOnlyWritePort(
                vehicle -> {
                    operations.add("insert:" + vehicle.code());
                    return false;
                }
        );

        ReadVehiclePort readVehiclePort = new ViewOnlyVehicleReadPort(
                code -> {
                    throw new AssertionError(
                            "Read port must not be called when every insert collides."
                    );
                }
        );

        VehicleCommandService service = new VehicleCommandService(
                writeVehiclePort,
                readVehiclePort,
                BusinessCodeGeneratorTestFactory.create(random),
                branchDirectory,
                Clock.systemUTC()
        );

        IllegalStateException failure = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(COMMAND)
        );

        assertEquals(
                "Failed to create a vehicle with a unique code after 5 attempts.",
                failure.getMessage()
        );

        List<String> expectedOperations = new ArrayList<>();
        expectedOperations.add("branch:" + BRANCH.code());

        for (String code : GENERATED_CODES) {
            expectedOperations.add("insert:" + code);
        }

        assertEquals(expectedOperations, operations);
        random.assertExhausted();
    }

    /**
     * Tạo view mô phỏng kết quả đọc lại sau khi chèn.
     *
     * @param code mã xe được chèn thành công
     * @return dữ liệu xe bản nháp có khóa chính do database mô phỏng cung cấp
     */
    private static VehicleDetail detail(String code) {
        return new VehicleDetail(
                100L,
                code,
                COMMAND.plateNumber(),
                COMMAND.ownershipType(),
                COMMAND.fuelType(),
                BRANCH.id(),
                VehicleStatus.DRAFT,
                COMMAND.documents().inspectionExpiresOn(),
                COMMAND.documents().liabilityInsuranceExpiresOn(),
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model(), null
        );
    }

    /**
     * Mô phỏng cổng ghi chỉ cho phép thao tác chèn trong luồng tạo xe.
     *
     * <p>Nếu service gọi cập nhật trạng thái, test phải thất bại ngay.
     */
    private static final class InsertOnlyWritePort
            implements WriteVehiclePort {

        private final Predicate<Vehicle> insertBehavior;

        /**
         * Nhận hành vi chèn do từng kịch bản kiểm thử cung cấp.
         *
         * @param insertBehavior hành vi trả kết quả chèn hoặc báo lỗi
         */
        private InsertOnlyWritePort(Predicate<Vehicle> insertBehavior) {
            this.insertBehavior = insertBehavior;
        }

        /**
         * Chuyển thao tác chèn cho hành vi của kịch bản.
         *
         * @param vehicle aggregate được service yêu cầu chèn
         * @return kết quả chèn do kịch bản quyết định
         */
        @Override
        public boolean insert(Vehicle vehicle) {
            return insertBehavior.test(vehicle);
        }

        /**
         * Từ chối cập nhật trạng thái trong luồng tạo xe.
         *
         * @param code mã xe
         * @param expectedStatus trạng thái cũ được yêu cầu
         * @param newStatus trạng thái mới được yêu cầu
         * @return không trả về vì phương thức luôn báo lỗi
         * @throws AssertionError nếu service gọi sai thao tác
         */
        @Override
        public boolean updateStatus(
                String code,
                VehicleStatus expectedStatus,
                VehicleStatus newStatus
        ) {
            throw new AssertionError(
                    "Status update must not be called during vehicle creation."
            );
        }
    }

    /**
     * Cung cấp nguồn số sinh lần lượt các hậu tố AAAAAA, BBBBBB và tiếp theo.
     *
     * <p>Kiểm cả số lần lấy số vượt quá và chưa đủ so với dự kiến,
     * giúp phát hiện service sinh mã sai số lần.
     */
    private static final class SequentialRandom implements RandomGenerator {

        private final int expectedDrawCount;

        private int drawCount;

        /**
         * Xác định số mã mà kịch bản cho phép sinh.
         *
         * @param expectedCodeCount số mã dự kiến, từ một đến 36
         */
        private SequentialRandom(int expectedCodeCount) {
            assertTrue(
                    expectedCodeCount >= 1 && expectedCodeCount <= 36,
                    "Expected code count must be between 1 and 36."
            );
            expectedDrawCount = expectedCodeCount * 6;
        }

        /**
         * Trả chỉ số ký tự cho nhóm sáu lần lấy số hiện tại.
         *
         * @param bound số ký tự trong bảng ký tự của bộ sinh mã
         * @return chỉ số ký tự dùng cho hậu tố
         * @throws AssertionError nếu sai giới hạn hoặc lấy quá số lần dự kiến
         */
        @Override
        public int nextInt(int bound) {
            assertEquals(36, bound);
            assertTrue(
                    drawCount < expectedDrawCount,
                    "Generator requested more random values than expected."
            );
            return drawCount++ / 6;
        }

        /**
         * Từ chối cách lấy số không thuộc kịch bản.
         *
         * @return không trả về vì phương thức luôn báo lỗi
         * @throws AssertionError nếu không sử dụng nextInt(bound)
         */
        @Override
        public long nextLong() {
            throw new AssertionError(
                    "Only nextInt(bound) is expected."
            );
        }

        /**
         * Chứng minh bộ sinh mã đã dùng đúng số giá trị dự kiến.
         */
        private void assertExhausted() {
            assertEquals(
                    expectedDrawCount,
                    drawCount,
                    "Generator must consume every expected random value."
            );
        }
    }
}
