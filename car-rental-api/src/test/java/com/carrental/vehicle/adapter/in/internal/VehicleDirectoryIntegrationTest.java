package com.carrental.vehicle.adapter.in.internal;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import com.carrental.identity.adapter.in.internal.ServiceCallerTestScope;
import com.carrental.identity.api.CallerRef;
import com.carrental.vehicle.api.VehicleDirectory;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.ChangeVehicleDailyRateCommand;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.in.ApproveVehicleUseCase;
import com.carrental.vehicle.application.port.in.ChangeVehicleDailyRateUseCase;
import com.carrental.vehicle.application.port.in.CreateVehicleUseCase;
import com.carrental.vehicle.application.port.in.SubmitVehicleForApprovalUseCase;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;

/** Trace Directory → use case → port → JDBC → PostgreSQL và branch.api thật, không mock quyền đọc admin. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
@Transactional
class VehicleDirectoryIntegrationTest {
    @Autowired private VehicleDirectory directory;
    @Autowired private CreateBranchUseCase createBranch;
    @Autowired private CreateVehicleUseCase createVehicle;
    @Autowired private SubmitVehicleForApprovalUseCase submit;
    @Autowired private ApproveVehicleUseCase approve;
    @Autowired private ChangeVehicleDailyRateUseCase changeRate;
    @Autowired @Qualifier("applicationClock") private Clock clock;

    /** Khách đặt đơn dùng được cổng ACTIVE; không bị quyền xem /admin giới hạn vào chi nhánh nhân viên. */
    @Test
    void customerCanResolveActiveVehicleAndRealBranchCode() {
        VehicleDetail active;
        try (var admin = ServiceCallerTestScope.admin()) {
            var draft = createDraft();
            submit.submitForApproval(SubmitVehicleForApprovalCommand.from(draft.code()));
            active = approve.approve(ApproveVehicleCommand.from(draft.code()));
        }
        try (var customer = ServiceCallerTestScope.open(new CallerRef("TK-CUST01", Set.of("CUSTOMER"), null))) {
            var ref = directory.findActiveByCode(active.code()).orElseThrow();
            assertEquals(active.id(), ref.id());
            assertEquals(active.code(), ref.code());
            assertEquals(active.branchCode(), ref.branchCode());
            assertNotNull(ref.branchCode());
            assertEquals(SPECIFICATIONS.seats(), ref.seats());
            assertEquals(SPECIFICATIONS.transmission().name(), ref.transmission());
        }
    }

    /** Xe chưa ACTIVE và mã không tồn tại có cùng kết quả rỗng; không lộ trạng thái duyệt qua cổng. */
    @Test
    void hidesDraftPendingAndMissingVehicles() {
        try (var admin = ServiceCallerTestScope.admin()) {
            var draft = createDraft();
            assertTrue(directory.findActiveByCode(draft.code()).isEmpty());
            submit.submitForApproval(SubmitVehicleForApprovalCommand.from(draft.code()));
            assertTrue(directory.findActiveByCode(draft.code()).isEmpty());
            assertTrue(directory.findActiveByCode("XE-NONE00").isEmpty());
        }
    }

    /** Đổi giá xe không thay hợp đồng của cổng đặt đơn; tiền vẫn phải lấy từ QuoteDirectory (BR-122). */
    @Test
    void currentPriceChangeDoesNotChangeBookingReference() {
        try (var admin = ServiceCallerTestScope.admin()) {
            var draft = createDraft();
            submit.submitForApproval(SubmitVehicleForApprovalCommand.from(draft.code()));
            approve.approve(ApproveVehicleCommand.from(draft.code()));
            var before = directory.findActiveByCode(draft.code()).orElseThrow();
            changeRate.changeDailyRate(ChangeVehicleDailyRateCommand.from(draft.code(), BigDecimal.valueOf(2_000_000)));
            assertEquals(before, directory.findActiveByCode(draft.code()).orElseThrow());
        }
    }

    /** Tạo qua use case thật, có giá và giấy tờ còn hạn; test tự rollback trong DB riêng. */
    private VehicleDetail createDraft() {
        var branch = createBranch.create(CreateBranchCommand.from(10.76, 106.66, "Booking test branch", "Local test address"));
        var expiry = LocalDate.now(clock).plusYears(1);
        return createVehicle.create(CreateVehicleCommand.from("T09-" + UUID.randomUUID(), OwnershipType.COMPANY,
                FuelType.PETROL, branch.code(), expiry, expiry, SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model(), BigDecimal.valueOf(1_000_000)));
    }
}
