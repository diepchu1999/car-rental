package com.carrental.vehicle.adapter.out.persistence;

import com.carrental.PostgresTestConfiguration;
import com.carrental.shared.sql.SqlLoader;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng cập nhật trạng thái xe dưới tranh chấp PostgreSQL thật.
 *
 * <p>Hai transaction cùng yêu cầu chuyển trạng thái theo BR-010
 * và status-flow mục 4. Chỉ một transaction được cập nhật thành công.
 *
 * <p>Test quan sát khóa trong PostgreSQL để xác nhận transaction thứ hai
 * thực sự phải chờ transaction thứ nhất, không chỉ chạy hai luồng độc lập.
 *
 * <p>Dữ liệu được commit thật trong container riêng của lớp test.
 * Context và container được đóng sau khi toàn bộ lớp chạy xong.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=3",
                "spring.datasource.hikari.connection-timeout=5000"
        }
)
@Import({
        PostgresTestConfiguration.class,
        VehicleStatusConcurrencyIntegrationTest.TransactionConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class VehicleStatusConcurrencyIntegrationTest {

    private static final String CURRENT_BACKEND_PID_SQL_PATH =
            "sql/vehicle/current_backend_pid_for_test.sql";

    private static final String IS_BACKEND_BLOCKED_BY_SQL_PATH =
            "sql/vehicle/is_backend_blocked_by_for_test.sql";

    private static final int WAIT_TIMEOUT_SECONDS = 10;
    private static final int HOLD_TIMEOUT_SECONDS = 30;
    private static final int TRANSACTION_TIMEOUT_SECONDS = 45;

    private static final Long BRANCH_ID = 42L;

    private static final LocalDate APPROVAL_DATE =
            LocalDate.of(2026, 9, 20);

    @Autowired
    private WriteVehiclePort writeVehiclePort;

    @Autowired
    private ReadVehiclePort readVehiclePort;

    @Autowired
    private SqlLoader sqlLoader;

    @Autowired
    private DataSource dataSource;

    @Autowired
    @Qualifier("vehicleStatusTransactionTemplate")
    private TransactionTemplate transactionTemplate;

    private NamedParameterJdbcTemplate diagnosticJdbcTemplate;
    private String currentBackendPidSql;
    private String isBackendBlockedBySql;

    /**
     * Chuẩn bị SQL quan sát và kiểm test không bị bọc trong transaction.
     *
     * <p>JDBC quan sát dùng chung DataSource với ứng dụng.
     * Khi gọi bên trong transaction, nó sử dụng đúng kết nối của transaction đó.
     *
     * <p>Timeout truy vấn quan sát được đặt riêng, không sửa JdbcTemplate
     * mà các adapter của ứng dụng đang dùng.
     */
    @BeforeEach
    void prepareTest() {
        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "The test must not run inside a test-managed transaction."
        );

        currentBackendPidSql = sqlLoader.load(
                CURRENT_BACKEND_PID_SQL_PATH
        );

        isBackendBlockedBySql = sqlLoader.load(
                IS_BACKEND_BLOCKED_BY_SQL_PATH
        );

        JdbcTemplate diagnosticJdbc = new JdbcTemplate(dataSource);
        diagnosticJdbc.setQueryTimeout(3);

        diagnosticJdbcTemplate =
                new NamedParameterJdbcTemplate(diagnosticJdbc);
    }

    /**
     * Chứng minh chỉ một transaction cập nhật được cùng trạng thái cũ.
     *
     * <p>Transaction thứ nhất giữ khóa sau UPDATE.
     * Chỉ cho phép commit sau khi PostgreSQL xác nhận transaction thứ hai
     * đang bị chính transaction thứ nhất chặn.
     *
     * <p>Sau commit, transaction thứ hai phải kiểm lại điều kiện trạng thái
     * và trả false. Dữ liệu ngoài trạng thái phải giữ nguyên.
     *
     * @param initial xe ở trạng thái đầu của kịch bản
     * @param transitioned xe được domain chuyển sang trạng thái đích
     * @throws Exception nếu điều phối luồng, chờ kết quả hoặc transaction thất bại
     */
    @ParameterizedTest
    @MethodSource("statusTransitions")
    void allowsOnlyOneUpdateWhenTransactionsContend(
            Vehicle initial,
            Vehicle transitioned
    ) throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            assertTrue(writeVehiclePort.insert(initial));
        });

        VehicleDetail before = readRequiredVehicle(initial.code());

        CountDownLatch allowFirstCommit = new CountDownLatch(1);

        CompletableFuture<Integer> firstUpdateReady =
                new CompletableFuture<>();

        CompletableFuture<Integer> secondConnectionReady =
                new CompletableFuture<>();

        ExecutorService workers = Executors.newFixedThreadPool(
                2,
                Thread.ofPlatform()
                        .name("vehicle-status-test-", 0)
                        .daemon(true)
                        .factory()
        );

        Throwable testFailure = null;

        try {
            Future<Boolean> first = workers.submit(
                    () -> updateAndHoldCommit(
                            initial,
                            transitioned,
                            firstUpdateReady,
                            allowFirstCommit
                    )
            );

            int firstPid = firstUpdateReady.get(
                    WAIT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            Future<Boolean> second = workers.submit(
                    () -> updateCompetingTransaction(
                            initial,
                            transitioned,
                            secondConnectionReady
                    )
            );

            int secondPid = secondConnectionReady.get(
                    WAIT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            assertNotEquals(
                    firstPid,
                    secondPid,
                    "The transactions must use different PostgreSQL sessions."
            );

            await("Second transaction is blocked by the first transaction")
                    .atMost(Duration.ofSeconds(WAIT_TIMEOUT_SECONDS))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        if (first.isDone()) {
                            first.get(
                                    WAIT_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                            );

                            throw new IllegalStateException(
                                    "The first transaction finished "
                                            + "before commit was allowed."
                            );
                        }

                        if (second.isDone()) {
                            second.get(
                                    WAIT_TIMEOUT_SECONDS,
                                    TimeUnit.SECONDS
                            );

                            throw new IllegalStateException(
                                    "The second transaction finished "
                                            + "before lock contention was observed."
                            );
                        }

                        return isBlockedBy(secondPid, firstPid);
                    });

            allowFirstCommit.countDown();

            assertTrue(
                    first.get(
                            WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    )
            );

            assertFalse(
                    second.get(
                            WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    )
            );

            VehicleDetail expected = new VehicleDetail(
                    before.id(),
                    before.code(),
                    before.plateNumber(),
                    before.ownershipType(),
                    before.fuelType(),
                    before.branchId(),
                    transitioned.status(),
                    before.inspectionExpiresOn(),
                    before.liabilityInsuranceExpiresOn(),
                    SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                    SPECIFICATIONS.make(), SPECIFICATIONS.model()
            );

            assertEquals(
                    expected,
                    readRequiredVehicle(initial.code())
            );
        } catch (Exception | Error failure) {
            testFailure = failure;
            throw failure;
        } finally {
            allowFirstCommit.countDown();

            try {
                stopWorkers(workers);
            } catch (RuntimeException | Error cleanupFailure) {
                if (testFailure != null) {
                    testFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * Chạy transaction thứ nhất và giữ quyền commit bằng chốt điều phối.
     *
     * <p>Chỉ báo PID sau khi UPDATE thành công và khóa đã được giữ.
     * Kết quả phương thức chỉ được trả sau khi TransactionTemplate commit.
     *
     * @param initial xe chứa trạng thái cũ cần khớp
     * @param transitioned xe chứa trạng thái đích
     * @param updateReady tín hiệu báo PID hoặc lỗi khởi chạy
     * @param allowCommit chốt cho phép kết thúc callback để commit
     * @return kết quả cập nhật sau khi transaction hoàn tất
     */
    private boolean updateAndHoldCommit(
            Vehicle initial,
            Vehicle transitioned,
            CompletableFuture<Integer> updateReady,
            CountDownLatch allowCommit
    ) {
        try {
            Boolean result = transactionTemplate.execute(status -> {
                int pid = currentBackendPid();

                boolean updated = writeVehiclePort.updateStatus(
                        initial.code(),
                        initial.status(),
                        transitioned.status()
                );

                assertTrue(
                        updated,
                        "The first transaction must update the vehicle."
                );

                updateReady.complete(pid);
                waitForCommitPermission(allowCommit);

                return updated;
            });

            assertNotNull(result);
            return result;
        } catch (RuntimeException | Error failure) {
            updateReady.completeExceptionally(failure);
            throw failure;
        }
    }

    /**
     * Chạy transaction thứ hai với cùng mã và trạng thái cũ.
     *
     * <p>Báo PID trước UPDATE để luồng test có thể quan sát việc chờ khóa.
     * Không thay thế adapter bằng mock và không tự giữ khóa bằng SQL khác.
     *
     * @param initial xe chứa trạng thái cũ cần khớp
     * @param transitioned xe chứa trạng thái đích
     * @param connectionReady tín hiệu báo PID hoặc lỗi khởi chạy
     * @return kết quả cập nhật sau khi transaction hoàn tất
     */
    private boolean updateCompetingTransaction(
            Vehicle initial,
            Vehicle transitioned,
            CompletableFuture<Integer> connectionReady
    ) {
        try {
            Boolean result = transactionTemplate.execute(status -> {
                connectionReady.complete(currentBackendPid());

                return writeVehiclePort.updateStatus(
                        initial.code(),
                        initial.status(),
                        transitioned.status()
                );
            });

            assertNotNull(result);
            return result;
        } catch (RuntimeException | Error failure) {
            connectionReady.completeExceptionally(failure);
            throw failure;
        }
    }

    /**
     * Chờ luồng test cho phép transaction thứ nhất commit.
     *
     * <p>Hết thời gian hoặc bị ngắt đều làm test thất bại,
     * để TransactionTemplate rollback thay vì giữ khóa vô hạn.
     *
     * @param allowCommit chốt được mở sau khi quan sát thấy tranh chấp
     */
    private static void waitForCommitPermission(
            CountDownLatch allowCommit
    ) {
        try {
            assertTrue(
                    allowCommit.await(
                            HOLD_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Timed out waiting for permission to commit."
            );
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while waiting for permission to commit.",
                    failure
            );
        }
    }

    /**
     * Lấy PID của kết nối đang tham gia transaction trên luồng hiện tại.
     *
     * @return PID của phiên PostgreSQL hiện tại
     */
    private int currentBackendPid() {
        assertTrue(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "Expected an active Spring-managed transaction."
        );

        Integer pid = diagnosticJdbcTemplate.queryForObject(
                currentBackendPidSql,
                new MapSqlParameterSource(),
                Integer.class
        );

        assertNotNull(pid);
        return pid;
    }

    /**
     * Kiểm PostgreSQL có ghi nhận một phiên đang bị phiên kia chặn hay không.
     *
     * <p>Được gọi ngoài hai transaction ghi, bằng kết nối quan sát thứ ba.
     *
     * @param waitingPid PID của phiên đang thử cập nhật
     * @param blockingPid PID của phiên đang giữ khóa
     * @return true khi PostgreSQL xác nhận quan hệ chờ khóa này
     */
    private boolean isBlockedBy(
            int waitingPid,
            int blockingPid
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("waitingPid", waitingPid, Types.INTEGER)
                .addValue("blockingPid", blockingPid, Types.INTEGER);

        Boolean blocked = diagnosticJdbcTemplate.queryForObject(
                isBackendBlockedBySql,
                parameters,
                Boolean.class
        );

        return Boolean.TRUE.equals(blocked);
    }

    /**
     * Đọc xe đã được commit để kiểm dữ liệu trước hoặc sau tranh chấp.
     *
     * @param code mã xe của kịch bản
     * @return thông tin chi tiết của xe
     */
    private VehicleDetail readRequiredVehicle(String code) {
        Optional<VehicleDetail> result =
                readVehiclePort.findByCode(code);

        assertTrue(
                result.isPresent(),
                "Expected vehicle to exist: " + code
        );

        return result.orElseThrow();
    }

    /**
     * Tạo hai kịch bản tranh chấp cho gửi duyệt và phê duyệt.
     *
     * <p>Mỗi kịch bản dùng mã và biển số riêng vì dữ liệu được commit thật.
     * Domain quyết định trạng thái đích, không gán trạng thái tùy ý.
     *
     * @return các cặp xe trước và sau chuyển trạng thái
     */
    private static Stream<Arguments> statusTransitions() {
        Vehicle draft = companyDraft(
                "XE-CONC01",
                "51H-123.45"
        );

        Vehicle pending = companyDraft(
                "XE-CONC02",
                "51H-678.90"
        ).submitForApproval();

        return Stream.of(
                Arguments.of(draft, draft.submitForApproval()),
                Arguments.of(
                        pending,
                        pending.approve(APPROVAL_DATE)
                )
        );
    }

    /**
     * Tạo dữ liệu xe công ty với giấy tờ còn hạn tại ngày duyệt cố định.
     *
     * <p>branchId là tham chiếu logic phục vụ test persistence;
     * kiểm chi nhánh tồn tại thuộc tầng application.
     *
     * @param code mã riêng của kịch bản
     * @param plateNumber biển số riêng của kịch bản
     * @return xe nháp dùng làm dữ liệu đầu vào
     */
    private static Vehicle companyDraft(
            String code,
            String plateNumber
    ) {
        return Vehicle.createDraft(
                code,
                plateNumber,
                OwnershipType.COMPANY,
                FuelType.HYBRID,
                BRANCH_ID,
                new VehicleDocuments(
                        APPROVAL_DATE.plusDays(30),
                        APPROVAL_DATE.plusDays(60)
                ), SPECIFICATIONS
        );
    }

    /**
     * Yêu cầu dừng các luồng và chờ kết thúc trong thời gian giới hạn.
     *
     * <p>Không dùng close của ExecutorService vì cần kiểm soát
     * thời gian chờ khi một tác vụ gặp lỗi.
     *
     * @param workers nhóm luồng riêng của kịch bản
     */
    private static void stopWorkers(ExecutorService workers) {
        workers.shutdownNow();

        try {
            assertTrue(
                    workers.awaitTermination(
                            WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    ),
                    "Concurrent workers did not stop within the timeout."
            );
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();

            throw new AssertionError(
                    "Interrupted while stopping concurrent workers.",
                    failure
            );
        }
    }

    /**
     * Cấu hình transaction dành riêng cho lớp test đồng thời.
     *
     * <p>Import cấu hình riêng tạo context riêng với container riêng,
     * tránh chia sẻ dữ liệu đã commit sang các bộ test khác.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionConfiguration {

        /**
         * Tạo công cụ mở transaction độc lập với mức READ_COMMITTED.
         *
         * <p>Cấu hình hoàn tất trước khi các luồng sử dụng.
         * Không thay đổi thuộc tính của template trong lúc test chạy.
         *
         * @param transactionManager bộ quản lý transaction của ứng dụng
         * @return template dùng cho chuẩn bị dữ liệu và hai luồng tranh chấp
         */
        @Bean
        TransactionTemplate vehicleStatusTransactionTemplate(
                PlatformTransactionManager transactionManager
        ) {
            TransactionTemplate template =
                    new TransactionTemplate(transactionManager);

            template.setPropagationBehavior(
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW
            );

            template.setIsolationLevel(
                    TransactionDefinition.ISOLATION_READ_COMMITTED
            );

            template.setTimeout(TRANSACTION_TIMEOUT_SECONDS);

            return template;
        }
    }
}
