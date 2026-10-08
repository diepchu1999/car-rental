package com.carrental.vehicle.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.shared.code.BusinessCodeGeneratorTestFactory;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import com.carrental.vehicle.application.command.ApproveVehicleCommand;
import com.carrental.vehicle.application.command.CreateVehicleCommand;
import com.carrental.vehicle.application.command.SubmitVehicleForApprovalCommand;
import com.carrental.vehicle.application.port.out.ReadVehiclePort;
import com.carrental.vehicle.application.port.out.VehiclePlateConflictException;
import com.carrental.vehicle.application.port.out.WriteVehiclePort;
import com.carrental.vehicle.application.view.VehicleDetail;
import com.carrental.vehicle.domain.FuelType;
import com.carrental.vehicle.domain.OwnershipType;
import com.carrental.vehicle.domain.Vehicle;
import com.carrental.vehicle.domain.VehicleDocuments;
import com.carrental.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Random;

import static com.carrental.vehicle.VehicleTestFixtures.SPECIFICATIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kiểm tra điều phối và đường lỗi của service xe theo BR-003, BR-005, BR-010.
 *
 * <p>Đường chuyển trạng thái tải aggregate trực tiếp.
 * View chỉ được đọc lại sau khi ghi thành công.
 *
 * <p>Port là mock; đồng hồ cố định đi qua ranh giới ngày UTC/Việt Nam.
 * Test không khởi động Spring và không kiểm chứng rollback thực tế.
 */
class VehicleCommandServiceTest {

    private static final String CODE = "XE-TEST01";

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-20T18:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh")
    );

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    private WriteVehiclePort write;
    private ReadVehiclePort read;
    private BranchDirectory branches;
    private VehicleCommandService service;

    /** Tạo bộ phụ thuộc mới để mỗi test độc lập với lịch sử gọi của test khác. */
    @BeforeEach
    void setUp() {
        write = mock(WriteVehiclePort.class);
        read = mock(ReadVehiclePort.class);
        branches = mock(BranchDirectory.class);
        service = new VehicleCommandService(
                write,
                read,
                BusinessCodeGeneratorTestFactory.create(new Random(7)),
                branches,
                CLOCK
        );
    }

    /** Chi nhánh không tồn tại phải chặn ghi và trả đúng lỗi theo BR-003. */
    @Test
    void rejectsMissingBranchBeforeWriting() {
        when(branches.findByCode("CN-TEST01")).thenReturn(Optional.empty());

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> service.create(command())
        );

        assertDomainFailure(
                failure,
                ErrorCode.BRANCH_NOT_FOUND,
                DomainException.Category.NOT_FOUND
        );
        verify(branches).findByCode("CN-TEST01");
        verifyNoInteractions(write, read);
    }

    /** Lỗi tra cứu chi nhánh không được chuyển thành lỗi không tìm thấy. */
    @Test
    void propagatesBranchFailure() {
        IllegalStateException expected =
                new IllegalStateException("Branch storage failed.");
        when(branches.findByCode(anyString())).thenThrow(expected);

        assertSame(expected, assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(command())
        ));
        verifyNoInteractions(write, read);
    }

    /** Trùng biển số được chuyển thành xung đột và không thử chèn xe khác. */
    @Test
    void translatesOnlyPlateConflictFromInsert() {
        existingBranch();
        when(write.insert(any())).thenThrow(
                new VehiclePlateConflictException(
                        new IllegalStateException("Simulated unique constraint failure.")
                )
        );

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> service.create(command())
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_PLATE_ALREADY_EXISTS,
                DomainException.Category.CONFLICT
        );
        verify(write).insert(any());
        verifyNoMoreInteractions(write);
        verifyNoInteractions(read);
    }

    /** Lỗi ghi không phải trùng biển số phải giữ nguyên và không tự thử lại. */
    @Test
    void propagatesUnexpectedInsertFailure() {
        existingBranch();
        IllegalStateException expected = new IllegalStateException("Insert failed.");
        when(write.insert(any())).thenThrow(expected);

        assertSame(expected, assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(command())
        ));
        verify(write).insert(any());
        verifyNoMoreInteractions(write);
        verifyNoInteractions(read);
    }

    /** Đọc lại rỗng sau chèn là lỗi nội bộ, không phải VEHICLE_NOT_FOUND. */
    @Test
    void failsWhenCreatedVehicleCannotBeReloaded() {
        existingBranch();
        when(write.insert(any())).thenReturn(true);
        when(read.findByCode(anyString())).thenReturn(Optional.empty());

        IllegalStateException failure = assertThrowsExactly(
                IllegalStateException.class,
                () -> service.create(command())
        );

        ArgumentCaptor<Vehicle> captured = ArgumentCaptor.forClass(Vehicle.class);
        verify(write).insert(captured.capture());

        assertEquals(
                "Created vehicle could not be reloaded: " + captured.getValue().code(),
                failure.getMessage()
        );
        verify(read).findByCode(captured.getValue().code());
        verifyNoMoreInteractions(write, read);
    }

    /** Lỗi đọc lại không được bắt nhầm thành lỗi biển số hoặc dẫn tới chèn lại. */
    @Test
    void doesNotTranslatePlateExceptionFromReload() {
        existingBranch();
        when(write.insert(any())).thenReturn(true);

        VehiclePlateConflictException expected = new VehiclePlateConflictException(
                new IllegalStateException("Unexpected failure from read port.")
        );
        when(read.findByCode(anyString())).thenThrow(expected);

        assertSame(expected, assertThrowsExactly(
                VehiclePlateConflictException.class,
                () -> service.create(command())
        ));
        verify(write).insert(any());
        verify(read).findByCode(anyString());
        verifyNoMoreInteractions(write, read);
    }

    /** Gửi duyệt tải aggregate trước, ghi trạng thái rồi mới đọc view trả về. */
    @Test
    void submitsDraftWithoutRequiringDocuments() {
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-TEST01")));
        Vehicle draft = aggregate(VehicleStatus.DRAFT, null, null);
        VehicleDetail pending = detail(VehicleStatus.PENDING_APPROVAL, null, null);

        when(read.loadAggregate(CODE)).thenReturn(Optional.of(draft));
        when(read.findByCode(CODE)).thenReturn(Optional.of(pending));
        when(write.updateStatus(
                CODE, VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL
        )).thenReturn(true);

        assertEquals(
                pending.withBranchCode("CN-TEST01"),
                service.submitForApproval(SubmitVehicleForApprovalCommand.from(CODE))
        );

        var order = inOrder(read, write);
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateStatus(
                CODE, VehicleStatus.DRAFT, VehicleStatus.PENDING_APPROVAL
        );
        order.verify(read).findByCode(CODE);

        verifyNoMoreInteractions(read, write);
        verify(branches).findById(42L);
        verifyNoMoreInteractions(branches);
    }

    /** Duyệt aggregate có giấy tờ còn hạn, ghi ACTIVE rồi đọc lại view. */
    @Test
    void approvesPendingVehicleAndReloads() {
        when(branches.findById(42L)).thenReturn(Optional.of(new BranchRef(42L, "CN-TEST01")));
        Vehicle pending = aggregate(
                VehicleStatus.PENDING_APPROVAL,
                TODAY.plusDays(1),
                TODAY.plusDays(2)
        );
        VehicleDetail active = detail(
                VehicleStatus.ACTIVE,
                TODAY.plusDays(1),
                TODAY.plusDays(2)
        );

        when(read.loadAggregate(CODE)).thenReturn(Optional.of(pending));
        when(read.findByCode(CODE)).thenReturn(Optional.of(active));
        when(write.updateStatus(
                CODE, VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE
        )).thenReturn(true);

        assertEquals(active.withBranchCode("CN-TEST01"), service.approve(ApproveVehicleCommand.from(CODE)));

        var order = inOrder(read, write);
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateStatus(
                CODE, VehicleStatus.PENDING_APPROVAL, VehicleStatus.ACTIVE
        );
        order.verify(read).findByCode(CODE);

        verifyNoMoreInteractions(read, write);
        verify(branches).findById(42L);
        verifyNoMoreInteractions(branches);
    }

    /** Thiếu chi nhánh sau ghi phải ném lỗi để transaction rollback, không trả response thiếu mã. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingBranchAfterTransition(boolean approval) {
        VehicleStatus initial = approval ? VehicleStatus.PENDING_APPROVAL : VehicleStatus.DRAFT;
        VehicleStatus target = approval ? VehicleStatus.ACTIVE : VehicleStatus.PENDING_APPROVAL;
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(aggregate(initial, TODAY.plusDays(1), TODAY.plusDays(1))));
        when(write.updateStatus(CODE, initial, target)).thenReturn(true);
        when(read.findByCode(CODE)).thenReturn(Optional.of(detail(target, TODAY.plusDays(1), TODAY.plusDays(1))));
        when(branches.findById(42L)).thenReturn(Optional.empty());
        var failure = assertThrowsExactly(IllegalStateException.class, () -> transition(approval));
        assertTrue(failure.getMessage().contains("missing branch"));
        verify(write).updateStatus(CODE, initial, target);
        verifyNoMoreInteractions(write);
        verify(branches).findById(42L);
        verifyNoMoreInteractions(branches);
    }

    /**
     * Không có aggregate phải báo lỗi không tìm thấy, không đọc view hoặc ghi.
     *
     * @param approval true để duyệt, false để gửi duyệt
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingVehicle(boolean approval) {
        when(read.loadAggregate(CODE)).thenReturn(Optional.empty());

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> transition(approval)
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_NOT_FOUND,
                DomainException.Category.NOT_FOUND
        );
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches);
    }

    /**
     * Gửi duyệt từ mọi trạng thái ngoài DRAFT phải bị domain từ chối.
     *
     * @param status trạng thái không được gửi duyệt
     */
    @ParameterizedTest
    @EnumSource(value = VehicleStatus.class, names = "DRAFT", mode = EnumSource.Mode.EXCLUDE)
    void rejectsInvalidSubmissionState(VehicleStatus status) {
        when(read.loadAggregate(CODE))
                .thenReturn(Optional.of(aggregate(status, null, null)));

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> transition(false)
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION,
                DomainException.Category.RULE_VIOLATION
        );
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches);
    }

    /**
     * Không được nhảy cóc hoặc duyệt lại xe đã ra khỏi trạng thái chờ duyệt.
     *
     * @param status trạng thái không được phê duyệt
     */
    @ParameterizedTest
    @EnumSource(
            value = VehicleStatus.class,
            names = "PENDING_APPROVAL",
            mode = EnumSource.Mode.EXCLUDE
    )
    void rejectsInvalidApprovalState(VehicleStatus status) {
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(status, TODAY.plusDays(1), TODAY.plusDays(1))
        ));

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> transition(true)
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION,
                DomainException.Category.RULE_VIOLATION
        );
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches);
    }

    /** Ngày Việt Nam đã hết hạn dù UTC còn hôm trước vẫn phải bị chặn theo BR-005. */
    @Test
    void usesVietnamDateRatherThanUtcDateForApproval() {
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(VehicleStatus.PENDING_APPROVAL, TODAY, TODAY.plusDays(1))
        ));

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> transition(true)
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_DOCUMENT_EXPIRED,
                DomainException.Category.RULE_VIOLATION
        );
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches);
    }

    /** Thiếu giấy tờ phải dừng trước cổng ghi theo BR-005. */
    @Test
    void rejectsMissingDocumentsBeforeWriting() {
        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(VehicleStatus.PENDING_APPROVAL, null, TODAY.plusDays(1))
        ));

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> transition(true)
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_DOCUMENT_MISSING,
                DomainException.Category.RULE_VIOLATION
        );
        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches);
    }

    /**
     * Thua cập nhật có điều kiện phải báo 409, không thử lại hoặc đọc view.
     *
     * @param approval thao tác cần kiểm
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsConcurrentStateChange(boolean approval) {
        VehicleStatus initial =
                approval ? VehicleStatus.PENDING_APPROVAL : VehicleStatus.DRAFT;
        VehicleStatus target =
                approval ? VehicleStatus.ACTIVE : VehicleStatus.PENDING_APPROVAL;

        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(initial, TODAY.plusDays(1), TODAY.plusDays(1))
        ));
        when(write.updateStatus(CODE, initial, target)).thenReturn(false);

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> transition(approval)
        );

        assertDomainFailure(
                failure,
                ErrorCode.VEHICLE_INVALID_STATUS_TRANSITION,
                DomainException.Category.CONFLICT
        );
        verify(read).loadAggregate(CODE);
        verify(write).updateStatus(CODE, initial, target);
        verifyNoMoreInteractions(read, write);
        verifyNoInteractions(branches);
    }

    /**
     * Đọc view rỗng sau chuyển trạng thái là lỗi nội bộ, không phải thiếu aggregate.
     *
     * @param approval thao tác cần kiểm
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingReloadAfterTransition(boolean approval) {
        VehicleStatus initial =
                approval ? VehicleStatus.PENDING_APPROVAL : VehicleStatus.DRAFT;

        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(initial, TODAY.plusDays(1), TODAY.plusDays(1))
        ));
        when(write.updateStatus(eq(CODE), any(), any())).thenReturn(true);
        when(read.findByCode(CODE)).thenReturn(Optional.empty());

        IllegalStateException failure = assertThrowsExactly(
                IllegalStateException.class,
                () -> transition(approval)
        );

        assertEquals(
                "Updated vehicle could not be reloaded: " + CODE,
                failure.getMessage()
        );

        var order = inOrder(read, write);
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateStatus(eq(CODE), any(), any());
        order.verify(read).findByCode(CODE);

        verifyNoMoreInteractions(read, write);
        verifyNoInteractions(branches);
    }

    /**
     * Lỗi cổng ghi trạng thái phải giữ nguyên, không bị coi là xung đột.
     *
     * @param approval thao tác cần kiểm
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesTransitionWriteFailure(boolean approval) {
        VehicleStatus initial =
                approval ? VehicleStatus.PENDING_APPROVAL : VehicleStatus.DRAFT;

        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(initial, TODAY.plusDays(1), TODAY.plusDays(1))
        ));

        IllegalStateException expected =
                new IllegalStateException("Status write failed.");
        when(write.updateStatus(eq(CODE), any(), any())).thenThrow(expected);

        assertSame(expected, assertThrowsExactly(
                IllegalStateException.class,
                () -> transition(approval)
        ));

        verify(read).loadAggregate(CODE);
        verify(write).updateStatus(eq(CODE), any(), any());
        verifyNoMoreInteractions(read, write);
        verifyNoInteractions(branches);
    }

    /**
     * Lỗi tải aggregate phải truyền nguyên trạng, không được thử đọc view thay thế.
     *
     * @param approval thao tác cần kiểm
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesAggregateLoadFailure(boolean approval) {
        IllegalStateException expected =
                new IllegalStateException("Aggregate loading failed.");
        when(read.loadAggregate(CODE)).thenThrow(expected);

        assertSame(expected, assertThrowsExactly(
                IllegalStateException.class,
                () -> transition(approval)
        ));

        verify(read).loadAggregate(CODE);
        verifyNoMoreInteractions(read);
        verifyNoInteractions(write, branches);
    }

    /**
     * Lỗi đọc lại sau cập nhật phải truyền ra ngoài và không dẫn tới cập nhật lần nữa.
     *
     * @param approval thao tác cần kiểm
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesTransitionReloadFailure(boolean approval) {
        VehicleStatus initial =
                approval ? VehicleStatus.PENDING_APPROVAL : VehicleStatus.DRAFT;

        when(read.loadAggregate(CODE)).thenReturn(Optional.of(
                aggregate(initial, TODAY.plusDays(1), TODAY.plusDays(1))
        ));
        when(write.updateStatus(eq(CODE), any(), any())).thenReturn(true);

        IllegalStateException expected =
                new IllegalStateException("Vehicle reload failed.");
        when(read.findByCode(CODE)).thenThrow(expected);

        assertSame(expected, assertThrowsExactly(
                IllegalStateException.class,
                () -> transition(approval)
        ));

        var order = inOrder(read, write);
        order.verify(read).loadAggregate(CODE);
        order.verify(write).updateStatus(eq(CODE), any(), any());
        order.verify(read).findByCode(CODE);

        verifyNoMoreInteractions(read, write);
        verifyNoInteractions(branches);
    }

    /** Mô phỏng chi nhánh tồn tại theo BR-003. */
    private void existingBranch() {
        when(branches.findByCode("CN-TEST01"))
                .thenReturn(Optional.of(new BranchRef(42L, "CN-TEST01")));
    }

    /**
     * Chọn thao tác để dùng chung kịch bản kỹ thuật.
     *
     * @param approval true để duyệt, false để gửi duyệt
     * @return view sau thao tác
     */
    private VehicleDetail transition(boolean approval) {
        return approval
                ? service.approve(ApproveVehicleCommand.from(CODE))
                : service.submitForApproval(SubmitVehicleForApprovalCommand.from(CODE));
    }

    /**
     * Tạo command bản nháp chưa có giấy tờ.
     *
     * @return command hợp lệ cho bước tạo xe
     */
    private static CreateVehicleCommand command() {
        return CreateVehicleCommand.from(
                "51H-123.45",
                OwnershipType.COMPANY,
                FuelType.PETROL,
                "CN-TEST01",
                null,
                null,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model()
        );
    }

    /**
     * Tạo aggregate độc lập với read view cho đường chuyển trạng thái.
     *
     * @param status trạng thái đang lưu
     * @param inspection hạn đăng kiểm
     * @param insurance hạn TNDS
     * @return aggregate mô phỏng kết quả loadAggregate
     */
    private static Vehicle aggregate(
            VehicleStatus status,
            LocalDate inspection,
            LocalDate insurance
    ) {
        return Vehicle.restore(
                CODE,
                "51H-123.45",
                OwnershipType.COMPANY,
                FuelType.PETROL,
                42L,
                status,
                new VehicleDocuments(inspection, insurance), SPECIFICATIONS
        );
    }

    /**
     * Tạo view mô phỏng kết quả đọc lại sau khi ghi.
     *
     * @param status trạng thái đã lưu sau thao tác
     * @param inspection hạn đăng kiểm
     * @param insurance hạn TNDS
     * @return view để service trả về cho bên gọi
     */
    private static VehicleDetail detail(
            VehicleStatus status,
            LocalDate inspection,
            LocalDate insurance
    ) {
        return new VehicleDetail(
                100L,
                CODE,
                "51H-123.45",
                OwnershipType.COMPANY,
                FuelType.PETROL,
                42L,
                status,
                inspection,
                insurance,
                SPECIFICATIONS.seats(), SPECIFICATIONS.transmission(),
                SPECIFICATIONS.make(), SPECIFICATIONS.model(), null
        );
    }

    /**
     * Kiểm đầy đủ mã, nhóm và thông báo công khai của lỗi.
     *
     * @param failure lỗi thực tế
     * @param code mã kỳ vọng
     * @param category nhóm kỳ vọng
     */
    private static void assertDomainFailure(
            DomainException failure,
            ErrorCode code,
            DomainException.Category category
    ) {
        assertEquals(code, failure.errorCode());
        assertEquals(category, failure.category());
        assertEquals(code.defaultMessage(), failure.getMessage());
    }
}
