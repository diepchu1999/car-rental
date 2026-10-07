package com.carrental.search;

import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.in.ApproveVehicleUseCase;
import com.carrental.vehicle.application.port.in.CreateVehicleUseCase;
import com.carrental.vehicle.application.port.in.SubmitVehicleForApprovalUseCase;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Transmission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Cố định thời gian và dựng fixture qua use case thật; chỉ tồn tại trong test Task 6. */
@TestConfiguration(proxyBeanMethods = false)
public class SearchIntegrationTestConfiguration {
    /**
     * Dùng 07:00 Việt Nam để kiểm cửa sổ đặt mà không phụ thuộc ngày chạy build.
     * Qualifier giúp cả availability (tiêm có tên) dùng cùng đồng hồ với booking/vehicle.
     */
    @Bean
    @Primary
    @Qualifier("applicationClock")
    Clock searchIntegrationClock() {
        return Clock.fixed(Instant.parse("2030-01-15T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
    }

    /** Lắp công cụ setup, không thay thế hay giả lập bất cứ directory nào. */
    @Bean
    Fixtures searchFixtures(CreateBranchUseCase branches, CreateVehicleUseCase vehicles,
            SubmitVehicleForApprovalUseCase submissions, ApproveVehicleUseCase approvals) {
        return new Fixtures(branches, vehicles, submissions, approvals);
    }

    /** Setup dữ liệu của test qua vòng đời DRAFT → PENDING_APPROVAL → ACTIVE theo BR-010. */
    public static final class Fixtures {
        private final CreateBranchUseCase branches;
        private final CreateVehicleUseCase vehicles;
        private final SubmitVehicleForApprovalUseCase submissions;
        private final ApproveVehicleUseCase approvals;

        /** Nhận use case thật đã được Spring bọc transaction. */
        private Fixtures(CreateBranchUseCase branches, CreateVehicleUseCase vehicles,
                SubmitVehicleForApprovalUseCase submissions, ApproveVehicleUseCase approvals) {
            this.branches = branches;
            this.vehicles = vehicles;
            this.submissions = submissions;
            this.approvals = approvals;
        }

        /** Tạo chi nhánh có tên và địa chỉ để đối chiếu kết quả BR-808. */
        public BranchDetail branch(double latitude, double longitude) {
            return branches.create(CreateBranchCommand.from(latitude, longitude,
                    "Search Branch", "123 Search Street"));
        }

        /** Tạo xe hợp lệ; model riêng giúp từng test cô lập tập tìm kiếm mà không xóa dữ liệu. */
        public VehicleDetail draft(String branchCode, String model) {
            LocalDate expiry = LocalDate.of(2031, 1, 1);
            return vehicles.create(CreateVehicleCommand.from("SEARCH-" + UUID.randomUUID(),
                    OwnershipType.COMPANY, FuelType.PETROL, branchCode, expiry, expiry,
                    5, Transmission.AUTOMATIC, "Toyota", model));
        }

        /** Gửi duyệt và duyệt bằng nghiệp vụ thật, không ghi tắt trạng thái vào bảng vehicle. */
        public VehicleDetail active(String branchCode, String model) {
            var vehicle = draft(branchCode, model);
            submissions.submitForApproval(SubmitVehicleForApprovalCommand.from(vehicle.code()));
            return approvals.approve(ApproveVehicleCommand.from(vehicle.code()));
        }
    }
}
