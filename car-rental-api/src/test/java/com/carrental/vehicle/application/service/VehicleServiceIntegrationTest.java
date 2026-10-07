package com.carrental.vehicle.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.shared.rental.RentalType;
import com.carrental.vehicle.api.VehicleSearchDirectory;
import com.carrental.vehicle.application.query.ListSearchVehiclesQuery;
import com.carrental.vehicle.application.view.VehicleSearchCandidate;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.in.ApproveVehicleUseCase;
import com.carrental.vehicle.application.port.in.CreateVehicleUseCase;
import com.carrental.vehicle.application.port.in.GetVehicleUseCase;
import com.carrental.vehicle.application.port.in.SubmitVehicleForApprovalUseCase;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.query.GetVehicleQuery;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Kiểm transaction của service xe với Spring proxy và PostgreSQL thật.
 *
 * <p>Không mở transaction trong test, nên phải quan sát được commit/rollback
 * do chính service sở hữu. Context và container riêng tránh làm bẩn test khác.
 * Quan hệ chi nhánh theo BR-003; chuyển trạng thái theo BR-005 và BR-010.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "car-rental.time-zone=Asia/Ho_Chi_Minh"
)
@Import({PostgresTestConfiguration.class, VehicleServiceIntegrationTest.ProbeConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class VehicleServiceIntegrationTest {

    @Autowired private CreateVehicleUseCase create;
    @Autowired private GetVehicleUseCase get;
    @Autowired private SubmitVehicleForApprovalUseCase submit;
    @Autowired private ApproveVehicleUseCase approve;
    @Autowired private CreateBranchUseCase createBranch;
    @Autowired private ReadProbe probe;
    @Autowired @Qualifier("vehicleReadAdapter") private ReadVehiclePort actualRead;
    @Autowired private Clock clock;
    @Autowired private VehicleSearchDirectory searchDirectory;



    /** Xóa trạng thái probe và bảo đảm test không che lỗi thiếu transaction ở service. */
    @BeforeEach
    void resetProbe() {
        probe.reset();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(ZoneId.of("Asia/Ho_Chi_Minh"), clock.getZone());
    }

    /** Tạo xe commit thật; đọc xe qua service mở transaction chỉ đọc. */
    @Test
    void commitsCreationAndUsesReadOnlyQueryTransaction() {
        CreateVehicleCommand command = command();
        VehicleDetail created = create.create(command);
        assertEquals(VehicleStatus.DRAFT, created.status());
        assertEquals(OwnershipType.COMPANY, created.ownershipType());
        assertEquals(command.plateNumber(), created.plateNumber());
        assertNotNull(created.branchId());
        assertTrue(created.id() > 0);
        assertTrue(probe.transactionActive);
        assertFalse(probe.transactionReadOnly);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(Optional.of(created), actualRead.findByCode(created.code()));

        probe.reset();
        assertEquals(created, get.get(GetVehicleQuery.from(created.code())));
        assertTrue(probe.transactionActive);
        assertTrue(probe.transactionReadOnly);
    }

    /** Directory tìm kiếm mở transaction chỉ đọc từ chính use case, không nhờ transaction của test. */
    @Test
    void searchUsesReadOnlyTransactionThroughDirectory() {
        VehicleDetail draft = create.create(command());
        submit.submitForApproval(SubmitVehicleForApprovalCommand.from(draft.code()));
        approve.approve(ApproveVehicleCommand.from(draft.code()));
        probe.reset();
        var result = searchDirectory.list(List.of(draft.branchId()), RentalType.DAILY,
                null, null, null, null, null, null);
        assertEquals(List.of(draft.code()), result.stream().map(view -> view.code()).toList());
        assertTrue(probe.transactionActive);
        assertTrue(probe.transactionReadOnly);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** Lỗi đọc lại sau INSERT phải rollback bản ghi đã nhìn thấy trong giao dịch. */
    @Test
    void rollsBackCreationWhenReloadFails() {
        CreateVehicleCommand command = command();
        probe.failAtRead = 1;
        assertThrowsExactly(IllegalStateException.class, () -> create.create(command));
        VehicleDetail inserted = probe.lastResult.orElseThrow();
        assertTrue(probe.transactionActive);
        assertFalse(probe.transactionReadOnly);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(actualRead.findByCode(inserted.code()).isEmpty());

        probe.reset();
        assertEquals(command.plateNumber(), create.create(command).plateNumber());
    }

    /** Gửi duyệt và duyệt phải commit đúng trạng thái, giữ nguyên các trường khác. */
    @Test
    void commitsBothTransitions() {
        VehicleDetail draft = create.create(command());
        probe.reset();
        VehicleDetail pending = submit.submitForApproval(SubmitVehicleForApprovalCommand.from(draft.code()));
        assertOnlyStatusChanged(draft, pending, VehicleStatus.PENDING_APPROVAL);
        assertTrue(probe.transactionActive);
        assertFalse(probe.transactionReadOnly);
        assertEquals(Optional.of(pending), actualRead.findByCode(draft.code()));

        probe.reset();
        VehicleDetail active = approve.approve(ApproveVehicleCommand.from(draft.code()));
        assertOnlyStatusChanged(pending, active, VehicleStatus.ACTIVE);
        assertTrue(probe.transactionActive);
        assertFalse(probe.transactionReadOnly);
        assertEquals(Optional.of(active), actualRead.findByCode(draft.code()));
    }

    /**
     * Lỗi lần đọc sau UPDATE phải phục hồi trạng thái trước thao tác.
     * @param approval true để kiểm duyệt, false để kiểm gửi duyệt
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollsBackTransitionWhenReloadFails(boolean approval) {
        VehicleDetail initial = create.create(command());
        if (approval) {
            initial = submit.submitForApproval(SubmitVehicleForApprovalCommand.from(initial.code()));
        }
        String code = initial.code();
        probe.reset();
        probe.failAtRead = 2;

        assertThrowsExactly(IllegalStateException.class, () -> {
            if (approval) {
                approve.approve(ApproveVehicleCommand.from(code));
            } else {
                submit.submitForApproval(SubmitVehicleForApprovalCommand.from(code));
            }
        });

        VehicleStatus attempted = approval ? VehicleStatus.ACTIVE : VehicleStatus.PENDING_APPROVAL;
        assertEquals(attempted, probe.lastResult.orElseThrow().status());
        assertTrue(probe.transactionActive);
        assertFalse(probe.transactionReadOnly);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(Optional.of(initial), actualRead.findByCode(code));
    }

    /** Trùng biển số qua PostgreSQL thật trả lỗi xung đột và không ảnh hưởng xe đã có. */
    @Test
    void translatesRealPlateConflictAndKeepsOriginalVehicle() {
        CreateVehicleCommand command = command();
        VehicleDetail original = create.create(command);
        probe.reset();
        DomainException failure = assertThrowsExactly(DomainException.class, () -> create.create(command));
        assertEquals(ErrorCode.VEHICLE_PLATE_ALREADY_EXISTS, failure.errorCode());
        assertEquals(DomainException.Category.CONFLICT, failure.category());
        assertEquals(0, probe.readCount);
        assertEquals(Optional.of(original), actualRead.findByCode(original.code()));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /**
     * Tạo chi nhánh thật và command có giấy tờ còn hạn, không dựa vào ngày cố định lâu dài.
     * @return command có biển số riêng cho mỗi test
     */
    private CreateVehicleCommand command() {
        String branchCode = createBranch.create(CreateBranchCommand.from(10.762622, 106.660172, "Test Branch", "123 Test Street")).code();
        LocalDate expiry = LocalDate.now(clock).plusDays(30);
        return CreateVehicleCommand.from("TEST-" + UUID.randomUUID(), OwnershipType.COMPANY,
                FuelType.HYBRID, branchCode, expiry, expiry.plusDays(1),
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model());
    }

    /**
     * So sánh toàn bộ view, chỉ cho phép trạng thái thay đổi.
     * @param before dữ liệu trước thao tác
     * @param after dữ liệu sau thao tác
     * @param status trạng thái đích
     */
    private static void assertOnlyStatusChanged(VehicleDetail before, VehicleDetail after, VehicleStatus status) {
        assertEquals(new VehicleDetail(before.id(), before.code(), before.plateNumber(), before.ownershipType(),
                before.fuelType(), before.branchId(), status, before.inspectionExpiresOn(),
                before.liabilityInsuranceExpiresOn(),
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model()), after);
    }

    /** Đăng ký probe chỉ trong context kiểm transaction của xe. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        /**
         * Bọc adapter thật và ưu tiên inject probe vào service.
         * @param delegate adapter đọc xe thật
         * @return probe quan sát transaction
         */
        @Bean
        @Primary
        ReadProbe vehicleReadProbe(@Qualifier("vehicleReadAdapter") ReadVehiclePort delegate) {
            return new ReadProbe(delegate);
        }
    }

    /** Quan sát kết quả thật và có thể gây lỗi sau lần đọc được chỉ định. */
    static final class ReadProbe implements ReadVehiclePort {
        /** Giữ đường tìm kiếm hoạt động khi context kiểm transaction bọc cổng đọc bằng probe. */
        @Override
        public List<VehicleSearchCandidate> findSearchCandidates(ListSearchVehiclesQuery query) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            transactionReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            return delegate.findSearchCandidates(query);
        }

        private final ReadVehiclePort delegate;
        private int failAtRead;
        private int readCount;
        private boolean transactionActive;
        private boolean transactionReadOnly;
        private Optional<VehicleDetail> lastResult = Optional.empty();

        /**
         * Nhận cổng đọc thật, không mở transaction riêng trong probe.
         * @param delegate adapter thật
         */
        ReadProbe(ReadVehiclePort delegate) {
            this.delegate = delegate;
        }

        /**
         * Đọc PostgreSQL trước khi chủ động gây lỗi để chứng minh rollback thực tế.
         * @param code mã xe
         * @return kết quả của adapter khi không bật lỗi
         */
        @Override
        public Optional<VehicleDetail> findByCode(String code) {
            readCount++;
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            transactionReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            lastResult = delegate.findByCode(code);
            if (readCount == failAtRead) {
                throw new IllegalStateException("Simulated vehicle reload failure.");
            }
            return lastResult;
        }

        /**
         * Tải aggregate qua adapter thật và ghi nhận transaction hiện tại.
         *
         * <p>Đếm chung với findByCode: khi chuyển trạng thái,
         * lần đọc thứ nhất tải aggregate, lần thứ hai đọc lại view.
         * Nhờ đó kịch bản gây lỗi sau UPDATE vẫn kiểm đúng rollback.
         *
         * <p>Không dựng aggregate từ lastResult hoặc VehicleDetail.
         *
         * @param code mã xe cần tải
         * @return aggregate từ adapter thật nếu không bật lỗi mô phỏng
         */
        @Override
        public Optional<Vehicle> loadAggregate(String code) {
            readCount++;
            transactionActive =
                    TransactionSynchronizationManager.isActualTransactionActive();
            transactionReadOnly =
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly();

            Optional<Vehicle> result = delegate.loadAggregate(code);

            if (readCount == failAtRead) {
                throw new IllegalStateException(
                        "Simulated vehicle aggregate loading failure."
                );
            }

            return result;
        }

        /** Xóa quan sát và cấu hình gây lỗi giữa các kịch bản. */
        void reset() {
            failAtRead = 0;
            readCount = 0;
            transactionActive = false;
            transactionReadOnly = false;
            lastResult = Optional.empty();
        }
    }
}
