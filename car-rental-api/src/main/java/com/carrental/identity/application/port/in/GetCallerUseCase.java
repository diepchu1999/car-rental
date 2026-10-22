package com.carrental.identity.application.port.in;

import com.carrental.identity.api.CallerRef;
import com.carrental.identity.application.query.GetCallerQuery;

/** Cổng dựng danh tính tối thiểu và phạm vi dữ liệu sau khi đồng bộ tài khoản, BR-803/809. */
public interface GetCallerUseCase {
    /**
     * Chỉ đọc bản gán, không tạo tài khoản hay tự cấp quyền.
     * Chưa gán thì branchId null; use case theo chi nhánh phải từ chối bằng STAFF_BRANCH_REQUIRED.
     * Có bản gán nhưng lệch vai hiện tại thì trả STAFF_ROLE_MISMATCH, không dùng vai cũ để tiếp tục.
     */
    CallerRef get(GetCallerQuery query);
}
