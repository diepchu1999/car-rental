package com.carrental.branch.application.service;

import com.carrental.branch.api.BranchDirectory;
import com.carrental.branch.api.BranchRef;
import com.carrental.branch.application.port.in.GetBranchUseCase;
import com.carrental.branch.application.port.out.ReadBranchPort;
import com.carrental.branch.application.query.GetBranchQuery;
import com.carrental.branch.application.view.BranchDetail;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Điều phối việc đọc thông tin chi nhánh theo mã nghiệp vụ.
 *
 * <p>Thông tin chi nhánh phục vụ quan hệ xe công ty với chi nhánh
 * và vị trí của xe theo BR-003.
 *
 * <p>Cổng GetBranchUseCase trả chi tiết cho luồng đọc trong module.
 * Cổng BranchDirectory chỉ cung cấp định danh tối thiểu
 * cho các module khác.
 *
 * <p>Service không thay đổi dữ liệu và không sinh mã mới.
 * Mỗi cổng giữ cách xử lý trường hợp không tìm thấy
 * theo hợp đồng riêng của nó.
 */
@Service
class BranchQueryService implements GetBranchUseCase, BranchDirectory {

    private final ReadBranchPort readBranchPort;

    /**
     * Nhận cổng đọc chi nhánh do Spring cung cấp.
     *
     * @param readBranchPort cổng tra cứu chi nhánh theo mã nghiệp vụ
     */
    BranchQueryService(ReadBranchPort readBranchPort) {
        this.readBranchPort = readBranchPort;
    }

    /**
     * Trả thông tin chi nhánh ứng với mã trong query.
     *
     * <p>Giữ nguyên mã đầu vào đã được query kiểm tra,
     * không cắt khoảng trắng hoặc thay đổi kiểu chữ.
     *
     * <p>Chỉ kết quả rỗng mới được chuyển thành lỗi không tìm thấy.
     * Lỗi truy cập dữ liệu được truyền ra ngoài nguyên trạng.
     *
     * @param query yêu cầu tra cứu đã được kiểm tra, không được null
     * @return thông tin chi tiết của chi nhánh tìm được
     * @throws DomainException nếu không tìm thấy chi nhánh,
     *                        với mã lỗi BRANCH_NOT_FOUND
     */
    @Override
    @Transactional(readOnly = true)
    public BranchDetail get(GetBranchQuery query) {
        return readBranchPort.findByCode(query.code())
                .orElseThrow(() -> DomainException.notFound(
                        ErrorCode.BRANCH_NOT_FOUND
                ));
    }

    /**
     * Tra cứu định danh chi nhánh để cung cấp cho module khác.
     *
     * <p>Tái dùng query để kiểm tra mã đầu vào khác null và không trắng.
     * Mã được giữ nguyên, không tự cắt khoảng trắng hoặc đổi kiểu chữ.
     *
     * <p>Chỉ chuyển id và code sang hợp đồng công khai BranchRef,
     * không đưa application view của branch qua ranh giới module.
     *
     * <p>Không tìm thấy trả Optional rỗng để module gọi quyết định
     * cách xử lý. Lỗi truy cập dữ liệu được truyền ra ngoài nguyên trạng.
     *
     * @param code mã nghiệp vụ của chi nhánh cần tìm
     * @return định danh chi nhánh nếu tìm thấy; Optional rỗng nếu không có;
     *         không bao giờ trả null
     * @throws DomainException nếu mã là null hoặc trắng,
     *                        với mã lỗi INVALID_REQUEST
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<BranchRef> findByCode(String code) {
        GetBranchQuery query = GetBranchQuery.from(code);

        return readBranchPort.findByCode(query.code())
                .map(view -> new BranchRef(
                        view.id(),
                        view.code()
                ));
    }
}