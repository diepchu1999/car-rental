package com.carrental.branch.application.port.out;

import com.carrental.branch.application.view.BranchDetail;

import java.util.Optional;

/**
 * Cổng đầu ra cho việc đọc dữ liệu chi nhánh.
 *
 * <p>Application phụ thuộc hợp đồng này thay vì JDBC
 * hoặc cách tổ chức câu truy vấn.
 */
public interface ReadBranchPort {

    /**
     * Tìm chi tiết chi nhánh theo mã nghiệp vụ.
     *
     * <p>Kết quả rỗng chỉ biểu thị không tìm thấy chi nhánh.
     * Lỗi truy cập dữ liệu phải được báo lỗi, không chuyển thành kết quả rỗng.
     *
     * <p>Service quyết định cách xử lý trường hợp không tìm thấy.
     *
     * @param code mã nghiệp vụ cần tìm, không được null hoặc trắng
     * @return chi tiết chi nhánh nếu tìm thấy; Optional rỗng nếu không có;
     *         không bao giờ trả null
     */
    Optional<BranchDetail> findByCode(String code);
}