package com.carrental.branch.application.query;

import com.carrental.shared.error.DomainException;

/** Tra chi nhánh bằng tham chiếu ID nội bộ theo ADR-0008; không dùng làm hợp đồng HTTP. */
public record FindBranchByIdQuery(long id) {
    /** Chặn ID không dương trước khi truy vấn; không suy mã nghiệp vụ từ ID. */
    public FindBranchByIdQuery {
        if (id <= 0) {
            throw DomainException.invalidInput("branchId must be positive.");
        }
    }
}
