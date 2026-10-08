package com.carrental.architecture.fixtures.r12allowed.vehicle.adapter.in.rest.admin.response;

/** Fixture hợp lệ: mã dạng chuỗi và số không mang tên ID vẫn được phép theo đúng phạm vi R12. */
public record AllowedResponse(String branchCode, String id, String branchId, int seats,
        double latitude, boolean valid, char tokenId) {
    /** Hằng static không phải thành phần record, không phải dữ liệu response. */
    public static final long INTERNAL_ID = 42;

    /** Ngay cả tên đuôi Id cũng hợp lệ với hằng static, vì không phải thành phần record. */
    public static final long internalId = 42;
}
