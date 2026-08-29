package com.carrental.branch.application.query;

import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Chứa mã chi nhánh cần tra cứu.
 *
 * <p>Query chỉ yêu cầu mã không thiếu hoặc trắng và giữ nguyên giá trị đầu vào.
 * Quy tắc định dạng khi tạo mã thuộc domain, không được lặp lại tại đây.
 *
 * <p>Application service chịu trách nhiệm tra cứu và báo lỗi nếu không tìm thấy.
 * Query không truy cập cơ sở dữ liệu và không phụ thuộc DTO của REST adapter.
 *
 * @param code mã chi nhánh cần tra cứu
 */
public record GetBranchQuery(String code) {

    /**
     * Bảo đảm query luôn chứa mã không thiếu hoặc trắng.
     *
     * @throws DomainException nếu mã là null hoặc chỉ chứa khoảng trắng
     */
    public GetBranchQuery {
        code = Validations.requiredText(code, "code");
    }

    /**
     * Tạo query từ mã đầu vào mà không cắt khoảng trắng hoặc đổi kiểu chữ.
     *
     * @param code mã đầu vào, có thể null nếu bị thiếu
     * @return query chứa mã đã được kiểm tra
     * @throws DomainException nếu mã là null hoặc chỉ chứa khoảng trắng
     */
    public static GetBranchQuery from(String code) {
        return new GetBranchQuery(code);
    }
}