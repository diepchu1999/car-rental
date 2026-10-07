package com.carrental.branch.application.service;

import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.BranchReadPortStub;
import com.carrental.branch.application.port.out.WriteBranchPort;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.branch.domain.Branch;
import com.carrental.branch.domain.BranchLocation;
import com.carrental.shared.code.BusinessCodeGeneratorTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra việc sinh mã, thử lại và đọc lại trong luồng tạo chi nhánh.
 *
 * <p>Vị trí phục vụ BR-003. Mã nghiệp vụ theo database-guideline
 * mục 2; việc đọc lại sau ghi theo module-architecture mục 10.
 *
 * <p>Cổng đọc và ghi được mô phỏng bằng lambda. Bộ sinh mã thật
 * nhận nguồn số kiểm soát được, nên kết quả không phụ thuộc may rủi.
 *
 * <p>Test không khởi động Spring hoặc cơ sở dữ liệu,
 * do đó không kiểm chứng proxy transaction hay rollback.
 */
class BranchCommandServiceTest {

    private static final BranchLocation LOCATION =
            new BranchLocation(10.762622, 106.660172);

    private static final CreateBranchCommand COMMAND =
            new CreateBranchCommand(LOCATION, "Test Branch", "123 Test Street");

    private static final List<String> GENERATED_CODES = List.of(
            "CN-AAAAAA",
            "CN-BBBBBB",
            "CN-CCCCCC",
            "CN-DDDDDD",
            "CN-EEEEEE"
    );

    /**
     * Kiểm tra thành công ngay hoặc sau các lần trùng mã.
     *
     * <p>Mỗi lần thử phải sinh mã tiếp theo và giữ nguyên vị trí.
     * Chỉ đọc lại sau lần chèn thành công, bằng đúng mã vừa chèn.
     *
     * <p>Trường hợp bốn lần trùng chứng minh lần thử thứ năm
     * vẫn được thực hiện, không dừng sớm hơn giới hạn.
     *
     * @param collisionsBeforeSuccess số lần cổng ghi báo trùng trước khi thành công
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void retriesCollisionsAndReturnsReloadedDetail(
            int collisionsBeforeSuccess
    ) {
        int attemptCount = collisionsBeforeSuccess + 1;
        String successfulCode = GENERATED_CODES.get(attemptCount - 1);

        SequentialRandom random = new SequentialRandom(attemptCount);
        BranchDetail expected = detail(successfulCode);

        List<Branch> insertedBranches = new ArrayList<>();
        List<String> operations = new ArrayList<>();

        WriteBranchPort writeBranchPort = branch -> {
            insertedBranches.add(branch);
            operations.add("insert:" + branch.code());
            return insertedBranches.size() == attemptCount;
        };

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(code -> {
            operations.add("read:" + code);
            return Optional.of(expected);
        });

        BranchCommandService service = new BranchCommandService(
                writeBranchPort,
                readBranchPort,
                BusinessCodeGeneratorTestFactory.create(random)
        );

        BranchDetail actual = service.create(COMMAND);

        assertSame(expected, actual);
        assertEquals(attemptCount, insertedBranches.size());

        List<String> expectedOperations = new ArrayList<>();

        for (int index = 0; index < attemptCount; index++) {
            String expectedCode = GENERATED_CODES.get(index);

            assertEquals(
                    new Branch(expectedCode, LOCATION, "Test Branch", "123 Test Street"),
                    insertedBranches.get(index)
            );

            expectedOperations.add("insert:" + expectedCode);
        }

        expectedOperations.add("read:" + successfulCode);

        assertEquals(expectedOperations, operations);
        random.assertExhausted();
    }

    /**
     * Chứng minh service dừng sau tổng cộng năm lần trùng mã.
     *
     * <p>Không được đọc lại vì chưa có lần chèn nào thành công.
     * Hết lượt thử là lỗi nội bộ, không phải lỗi không tìm thấy.
     */
    @Test
    void stopsAfterFiveCodeCollisionsWithoutReading() {
        SequentialRandom random = new SequentialRandom(5);
        List<String> insertedCodes = new ArrayList<>();

        WriteBranchPort writeBranchPort = branch -> {
            insertedCodes.add(branch.code());
            return false;
        };

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(code -> {
            throw new AssertionError(
                    "Read port must not be called when every insert collides."
            );
        });

        BranchCommandService service = new BranchCommandService(
                writeBranchPort,
                readBranchPort,
                BusinessCodeGeneratorTestFactory.create(random)
        );

        IllegalStateException failure = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(COMMAND)
        );

        assertEquals(
                "Failed to create a branch with a unique code after 5 attempts.",
                failure.getMessage()
        );

        assertEquals(GENERATED_CODES, insertedCodes);
        random.assertExhausted();
    }

    /**
     * Chứng minh lỗi từ cổng ghi được truyền ra ngoài nguyên trạng.
     *
     * <p>Lỗi lưu trữ không được coi là trùng mã để thử tiếp.
     * Cổng đọc cũng không được gọi sau lần ghi thất bại.
     */
    @Test
    void propagatesWriteFailureWithoutRetryingOrReading() {
        SequentialRandom random = new SequentialRandom(1);
        List<String> insertedCodes = new ArrayList<>();

        IllegalStateException storageFailure =
                new IllegalStateException("Simulated write failure.");

        WriteBranchPort writeBranchPort = branch -> {
            insertedCodes.add(branch.code());
            throw storageFailure;
        };

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(code -> {
            throw new AssertionError(
                    "Read port must not be called after a write failure."
            );
        });

        BranchCommandService service = new BranchCommandService(
                writeBranchPort,
                readBranchPort,
                BusinessCodeGeneratorTestFactory.create(random)
        );

        IllegalStateException actual = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(COMMAND)
        );

        assertSame(storageFailure, actual);
        assertEquals(List.of("CN-AAAAAA"), insertedCodes);
        random.assertExhausted();
    }

    /**
     * Chứng minh không đọc lại được bản ghi vừa chèn là lỗi nội bộ.
     *
     * <p>Service không chuyển thành BRANCH_NOT_FOUND
     * và không tạo thêm chi nhánh để thử khắc phục lỗi đọc lại.
     */
    @Test
    void reportsInternalFailureWhenCreatedBranchCannotBeReloaded() {
        SequentialRandom random = new SequentialRandom(1);
        List<String> operations = new ArrayList<>();

        WriteBranchPort writeBranchPort = branch -> {
            operations.add("insert:" + branch.code());
            return true;
        };

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(code -> {
            operations.add("read:" + code);
            return Optional.empty();
        });

        BranchCommandService service = new BranchCommandService(
                writeBranchPort,
                readBranchPort,
                BusinessCodeGeneratorTestFactory.create(random)
        );

        IllegalStateException failure = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(COMMAND)
        );

        assertEquals(
                "Created branch could not be reloaded: CN-AAAAAA",
                failure.getMessage()
        );

        assertEquals(
                List.of("insert:CN-AAAAAA", "read:CN-AAAAAA"),
                operations
        );

        random.assertExhausted();
    }

    /**
     * Chứng minh lỗi từ cổng đọc lại được truyền ra ngoài nguyên trạng.
     *
     * <p>Chi nhánh đã được chèn trong giao dịch hiện tại,
     * nên service không được sinh mã khác và chèn thêm lần nữa.
     */
    @Test
    void propagatesReloadFailureWithoutInsertingAgain() {
        SequentialRandom random = new SequentialRandom(1);
        List<String> operations = new ArrayList<>();

        IllegalStateException storageFailure =
                new IllegalStateException("Simulated reload failure.");

        WriteBranchPort writeBranchPort = branch -> {
            operations.add("insert:" + branch.code());
            return true;
        };

        ReadBranchPort readBranchPort = BranchReadPortStub.byCode(code -> {
            operations.add("read:" + code);
            throw storageFailure;
        });

        BranchCommandService service = new BranchCommandService(
                writeBranchPort,
                readBranchPort,
                BusinessCodeGeneratorTestFactory.create(random)
        );

        IllegalStateException actual = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(COMMAND)
        );

        assertSame(storageFailure, actual);

        assertEquals(
                List.of("insert:CN-AAAAAA", "read:CN-AAAAAA"),
                operations
        );

        random.assertExhausted();
    }

    /**
     * Tạo view mô phỏng dữ liệu cổng đọc trả về.
     *
     * @param code mã của chi nhánh đã chèn thành công
     * @return view chứa khóa nội bộ và vị trí kiểm thử
     */
    private static BranchDetail detail(String code) {
        return new BranchDetail(
                42L,
                code,
                LOCATION.latitude(),
                LOCATION.longitude(), "Test Branch", "123 Test Street"
        );
    }

    /**
     * Cung cấp dãy số khiến các mã lần lượt có hậu tố AAAAAA, BBBBBB.
     *
     * <p>Mỗi nhóm sáu lần lấy số trả cùng một vị trí trong bảng ký tự.
     * Nhóm tiếp theo tăng vị trí lên một đơn vị.
     *
     * <p>Nguồn số kiểm tra cả việc lấy quá nhiều hoặc quá ít giá trị,
     * giúp phát hiện service sinh mã sai số lần.
     */
    private static final class SequentialRandom implements RandomGenerator {

        private final int expectedDrawCount;

        private int drawCount;

        /**
         * Xác định số lần sinh mã mà kịch bản cho phép.
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
         * Trả vị trí ký tự tiếp theo và kiểm số lần lấy số.
         *
         * @param bound số ký tự mà bộ sinh mã cho phép lựa chọn
         * @return vị trí ký tự của nhóm sáu lần lấy số hiện tại
         * @throws AssertionError nếu sai giới hạn hoặc lấy quá số lần dự kiến
         */
        @Override
        public int nextInt(int bound) {
            assertEquals(
                    36,
                    bound,
                    "Generator must use the expected 36-character alphabet."
            );

            assertTrue(
                    drawCount < expectedDrawCount,
                    "Generator requested more random values than expected."
            );

            return drawCount++ / 6;
        }

        /**
         * Từ chối phương thức ngẫu nhiên không thuộc kịch bản kiểm thử.
         *
         * @return không trả về vì phương thức luôn báo lỗi
         * @throws AssertionError khi bộ sinh mã không gọi nextInt(bound)
         */
        @Override
        public long nextLong() {
            throw new AssertionError(
                    "Only nextInt(bound) is expected."
            );
        }

        /**
         * Chứng minh bộ sinh mã đã dùng đủ số giá trị mà test dự kiến.
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
