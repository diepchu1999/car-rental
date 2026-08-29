package com.carrental.branch.application.port.out;

import com.carrental.branch.domain.Branch;

/**
 * Cổng đầu ra cho việc ghi dữ liệu chi nhánh.
 *
 * <p>Nhận mô hình domain, không nhận read view,
 * theo quy tắc R4 trong module-architecture.
 *
 * <p>Mã nghiệp vụ do application sinh và được bảo vệ
 * bằng ràng buộc duy nhất theo database-guideline mục 2.
 */
public interface WriteBranchPort {

    /**
     * Chèn một chi nhánh mới với mã nghiệp vụ đã được sinh.
     *
     * <p>Việc kiểm tra trùng mã và chèn phải được cơ sở dữ liệu
     * bảo vệ nguyên tử, không dùng cách đọc trước rồi mới ghi.
     *
     * <p>Nếu trùng mã, không chèn và không cập nhật chi nhánh đang tồn tại.
     * Service có thể sinh mã khác để thử lại.
     *
     * <p>Các lỗi lưu trữ khác phải được báo lỗi,
     * không được chuyển thành giá trị false.
     *
     * <p>Kết quả thành công chỉ xác nhận thao tác chèn trong giao dịch
     * hiện tại, không có nghĩa giao dịch đã được commit.
     *
     * @param branch chi nhánh có mã và vị trí hợp lệ, không được null
     * @return true nếu chèn được một bản ghi mới;
     *         false chỉ khi không chèn do trùng mã nghiệp vụ
     */
    boolean insert(Branch branch);
}