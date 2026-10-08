package com.carrental.branch.application.service;

import com.carrental.PostgresTestConfiguration;
import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.application.command.CreateBranchCommand;
import com.carrental.branch.application.port.in.CreateBranchUseCase;
import com.carrental.branch.application.port.in.GetBranchUseCase;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.ListNearbyBranchesQuery;
import com.carrental.branch.application.view.BranchDistanceSummary;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm chứng service chi nhánh với Spring và PostgreSQL thật.
 *
 * <p>Vị trí phục vụ BR-003. Transaction và việc đọc lại sau ghi
 * tuân theo module-architecture mục 10.
 *
 * <p>Test không mở transaction riêng. Service phải tự tạo transaction,
 * commit khi thành công và rollback khi phát sinh lỗi runtime.
 *
 * <p>Cấu hình quan sát chỉ thuộc lớp test này. Context và container
 * riêng được đóng sau khi hoàn thành toàn bộ lớp test.
 *
 * <p>Các test chạy tuần tự vì dùng chung bean ghi nhận quan sát.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({
        PostgresTestConfiguration.class,
        BranchServiceIntegrationTest.ProbeConfiguration.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class BranchServiceIntegrationTest {

    @Autowired
    private CreateBranchUseCase createBranchUseCase;

    @Autowired
    private GetBranchUseCase getBranchUseCase;

    @Autowired
    private BranchDirectory branchDirectory;

    @Autowired
    @Qualifier("branchReadAdapter")
    private ReadBranchPort actualReadBranchPort;

    @Autowired
    private ReadBranchProbe readBranchProbe;

    /**
     * Xóa quan sát của kịch bản trước và kiểm tra test không có transaction.
     *
     * <p>Nếu test tự mở transaction, nó có thể che mất việc service
     * thiếu cấu hình transaction.
     */
    @BeforeEach
    void resetProbe() {
        readBranchProbe.reset();

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive(),
                "The test must not start its own transaction."
        );
    }

    /**
     * Chứng minh service tạo chi nhánh commit dữ liệu thành công.
     *
     * <p>Lần đọc lại trong luồng tạo phải nằm trong transaction ghi.
     * Sau khi service trả về, adapter thật phải đọc được bản ghi
     * từ bên ngoài transaction đã kết thúc.
     *
     * <p>Service truy vấn cùng chi nhánh phải chạy trong transaction chỉ đọc.
     */
    @Test
    void commitsCreatedBranchAndQueriesItInReadOnlyTransaction() {
        CreateBranchCommand command = CreateBranchCommand.from(
                10.762622,
                106.660172, "Test Branch", "123 Test Street"
        );

        BranchDetail created = createBranchUseCase.create(command);

        assertTrue(created.id() > 0);
        assertTrue(created.code().matches("CN-[A-Z0-9]{6}"));

        assertEquals(
                command.location().latitude(),
                created.latitude()
        );

        assertEquals(
                command.location().longitude(),
                created.longitude()
        );

        assertTrue(readBranchProbe.transactionActive);
        assertFalse(readBranchProbe.transactionReadOnly);
        assertEquals(
                Optional.of(created),
                readBranchProbe.lastResult
        );

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );

        assertEquals(
                Optional.of(created),
                actualReadBranchPort.findByCode(created.code())
        );

        readBranchProbe.reset();

        BranchDetail found = getBranchUseCase.get(
                GetBranchQuery.from(created.code())
        );

        assertEquals(created, found);
        assertTrue(readBranchProbe.transactionActive);
        assertTrue(readBranchProbe.transactionReadOnly);

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );
    }

    /**
     * Chứng minh truy vấn không tìm thấy trả đúng lỗi nghiệp vụ.
     *
     * <p>Mã kiểm thử không thể được tạo theo định dạng CN-<6>,
     * nên không phụ thuộc vào khả năng trùng mã ngẫu nhiên.
     * Query cho phép mọi chuỗi không trắng và giữ nguyên giá trị.
     */
    @Test
    void reportsMissingBranchFromReadOnlyTransaction() {
        GetBranchQuery query = GetBranchQuery.from("missing-branch");

        DomainException failure = assertThrowsExactly(
                DomainException.class,
                () -> getBranchUseCase.get(query)
        );

        assertEquals(
                ErrorCode.BRANCH_NOT_FOUND,
                failure.errorCode()
        );

        assertEquals(
                DomainException.Category.NOT_FOUND,
                failure.category()
        );

        assertTrue(readBranchProbe.lastResult.isEmpty());
        assertTrue(readBranchProbe.transactionActive);
        assertTrue(readBranchProbe.transactionReadOnly);

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );
    }

    /**
     * Chứng minh lỗi đọc lại làm rollback bản ghi vừa chèn.
     *
     * <p>Probe gọi adapter thật và nhìn thấy bản ghi trong transaction,
     * sau đó mới cố ý ném lỗi.
     *
     * <p>Khi service kết thúc bằng lỗi, một lần đọc qua adapter thật
     * bên ngoài transaction phải không còn tìm thấy bản ghi đó.
     */
    @Test
    void rollsBackInsertedBranchWhenReloadFails() {
        IllegalStateException expectedFailure =
                new IllegalStateException("Simulated reload failure.");

        readBranchProbe.failNextReadWith(expectedFailure);

        CreateBranchCommand command = CreateBranchCommand.from(
                21.028511,
                105.804817, "Test Branch", "123 Test Street"
        );

        IllegalStateException actualFailure = assertThrowsExactly(
                IllegalStateException.class,
                () -> createBranchUseCase.create(command)
        );

        assertSame(expectedFailure, actualFailure);
        assertTrue(readBranchProbe.transactionActive);
        assertFalse(readBranchProbe.transactionReadOnly);

        assertTrue(
                readBranchProbe.lastResult.isPresent(),
                "The inserted branch must be visible before the simulated failure."
        );

        BranchDetail inserted = readBranchProbe.lastResult.orElseThrow();

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );

        assertTrue(
                actualReadBranchPort.findByCode(inserted.code()).isEmpty(),
                "The inserted branch must be rolled back after reload failure."
        );
    }

    /**
     * Chứng minh cổng liên module đọc được chi nhánh đã commit.
     *
     * <p>Chi nhánh được tạo qua use case thật để phục vụ quan hệ
     * xe công ty với chi nhánh theo BR-003.
     *
     * <p>Cổng BranchDirectory được Spring cung cấp, trả đúng id và code
     * và mở transaction chỉ đọc khi được gọi ngoài transaction khác.
     */
    @Test
    void readsReferenceThroughDirectoryInReadOnlyTransaction() {
        BranchDetail created = createBranchUseCase.create(
                CreateBranchCommand.from(
                        10.762622,
                        106.660172, "Test Branch", "123 Test Street"
                )
        );

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );

        readBranchProbe.reset();

        Optional<BranchRef> found = branchDirectory.findByCode(
                created.code()
        );

        assertEquals(
                Optional.of(new BranchRef(
                        created.id(),
                        created.code()
                )),
                found
        );

        assertEquals(
                Optional.of(created),
                readBranchProbe.lastResult
        );
        assertTrue(readBranchProbe.transactionActive);
        assertTrue(readBranchProbe.transactionReadOnly);

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );
    }

    /**
     * Chứng minh cổng liên module trả kết quả rỗng khi không có chi nhánh.
     *
     * <p>Truy vấn đi qua adapter và PostgreSQL thật,
     * trong transaction chỉ đọc do service mở.
     *
     * <p>Mã kiểm thử không thể trùng mã do ứng dụng sinh,
     * nên kết quả không phụ thuộc dữ liệu của các test trước.
     */
    @Test
    void returnsEmptyThroughDirectoryInReadOnlyTransaction() {
        Optional<BranchRef> found = branchDirectory.findByCode(
                "missing-branch"
        );

        assertEquals(Optional.empty(), found);
        assertTrue(readBranchProbe.lastResult.isEmpty());
        assertTrue(readBranchProbe.transactionActive);
        assertTrue(readBranchProbe.transactionReadOnly);

        assertFalse(
                TransactionSynchronizationManager.isActualTransactionActive()
        );
    }

    /** Truy vấn bán kính qua Directory mở transaction chỉ đọc, không tự mở ở adapter. */
    @Test
    void readsNearbyBranchesThroughDirectoryInReadOnlyTransaction() {
        BranchDetail created = createBranchUseCase.create(
                CreateBranchCommand.from(10.762622, 106.660172, "Nearby Branch", "123 Street"));
        readBranchProbe.reset();
        var found = branchDirectory.findWithinRadius(10.762622, 106.660172, 1.0);
        assertTrue(found.stream().anyMatch(branch -> branch.code().equals(created.code())));
        assertTrue(readBranchProbe.transactionActive);
        assertTrue(readBranchProbe.transactionReadOnly);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /**
     * Đăng ký lớp quan sát cổng đọc dành riêng cho integration test này.
     *
     * <p>Service nhận probe thông qua Primary.
     * Probe vẫn chuyển truy vấn đến persistence adapter thật.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        /**
         * Tạo probe bọc đúng bean persistence adapter đang có.
         *
         * <p>Qualifier chọn adapter thật, tránh inject ngược
         * chính probe vào dependency của nó.
         *
         * @param delegate adapter đọc chi nhánh thật
         * @return probe được ưu tiên khi service yêu cầu ReadBranchPort
         */
        @Bean
        @Primary
        ReadBranchProbe readBranchProbe(
                @Qualifier("branchReadAdapter") ReadBranchPort delegate
        ) {
            return new ReadBranchProbe(delegate);
        }
    }

    /**
     * Quan sát trạng thái transaction và kết quả của cổng đọc thật.
     *
     * <p>Probe có thể gây một lỗi runtime sau khi truy vấn thật hoàn tất.
     * Không tự tạo dữ liệu giả hoặc tự mở transaction.
     */
    static final class ReadBranchProbe implements ReadBranchPort {

        /** Chuyển nguyên truy vấn ID cho adapter thật; không giả dữ liệu cross-module. */
        @Override
        public Optional<BranchDetail> findById(long id) {
            return delegate.findById(id);
        }

        /** Chuyển truy vấn địa lý qua adapter thật và ghi nhận transaction do service mở. */
        @Override
        public java.util.List<BranchDistanceSummary> findWithinRadius(ListNearbyBranchesQuery query) {
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            transactionReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            return delegate.findWithinRadius(query);
        }

        private final ReadBranchPort delegate;

        private Optional<BranchDetail> lastResult = Optional.empty();

        private boolean transactionActive;

        private boolean transactionReadOnly;

        private RuntimeException nextFailure;

        /**
         * Nhận adapter thật để thực hiện truy vấn PostgreSQL.
         *
         * @param delegate cổng đọc dữ liệu thật
         */
        ReadBranchProbe(ReadBranchPort delegate) {
            this.delegate = delegate;
        }

        /**
         * Ghi nhận transaction, thực hiện truy vấn thật và tùy chọn gây lỗi.
         *
         * <p>Lỗi được ném sau khi đã lưu kết quả truy vấn,
         * giúp test biết bản ghi có tồn tại trước khi rollback hay không.
         *
         * @param code mã chi nhánh cần tìm
         * @return kết quả từ adapter thật nếu không bật lỗi mô phỏng
         * @throws RuntimeException nếu đã cấu hình lỗi cho lần đọc này
         */
        @Override
        public Optional<BranchDetail> findByCode(String code) {
            transactionActive =
                    TransactionSynchronizationManager.isActualTransactionActive();

            transactionReadOnly =
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly();

            lastResult = delegate.findByCode(code);

            if (nextFailure != null) {
                RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }

            return lastResult;
        }

        /**
         * Cấu hình một lỗi sẽ được ném sau lần truy vấn thật tiếp theo.
         *
         * @param failure lỗi runtime mà kịch bản muốn mô phỏng
         * @throws NullPointerException nếu lỗi được cung cấp là null
         */
        void failNextReadWith(RuntimeException failure) {
            nextFailure = Objects.requireNonNull(
                    failure,
                    "failure must not be null."
            );
        }

        /**
         * Xóa toàn bộ quan sát và lỗi đang chờ của kịch bản trước.
         */
        void reset() {
            lastResult = Optional.empty();
            transactionActive = false;
            transactionReadOnly = false;
            nextFailure = null;
        }
    }
}
