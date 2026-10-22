package com.carrental.identity.domain;

/**
 * Danh sách vai của tài khoản theo BR-801; tên khớp với realm Keycloak.
 *
 * <p>Phân loại phạm vi theo BR-803 không đồng nghĩa với cấp quyền thao tác:
 * kế toán và quản lý vận hành không có toàn quyền của ADMIN.
 * Các vai giai đoạn sau chỉ được khai báo, không được xem như nhân viên công ty.
 */
public enum AccountRole {
    /** Khách thuê xe, có thể đồng thời giữ một vai nhân viên theo BR-809. */
    CUSTOMER,
    /** Nhân viên vận hành thuộc đúng một chi nhánh. */
    BRANCH_STAFF,
    /** Quản lý thuộc đúng một chi nhánh. */
    BRANCH_MANAGER,
    /** Nhân viên giao xe thuộc đúng một chi nhánh. */
    DELIVERY_STAFF,
    /** Tài xế công ty thuộc đúng một chi nhánh. */
    DRIVER,
    /** Kế toán cấp công ty, chỉ có quyền trong công việc của vai. */
    ACCOUNTANT,
    /** Quản lý vận hành cấp công ty, không thay thế ADMIN. */
    OPERATIONS_MANAGER,
    /** Quản trị toàn quyền, không gắn với một chi nhánh. */
    ADMIN,
    /** Đối tác cá nhân, dành cho giai đoạn 2. */
    HOST,
    /** Đối tác doanh nghiệp, dành cho giai đoạn 3. */
    BUSINESS_PARTNER,
    /** Tài khoản con của đối tác doanh nghiệp, dành cho giai đoạn 3. */
    BUSINESS_PARTNER_STAFF,
    /** Tài xế tự do, không phải tài xế nhân viên công ty. */
    FREELANCE_DRIVER;

    /** Xác định bảy vai nhân viên công ty theo BR-801 và BR-809. */
    public boolean isEmployee() {
        return switch (this) {
            case BRANCH_STAFF, BRANCH_MANAGER, DELIVERY_STAFF, DRIVER,
                    ACCOUNTANT, OPERATIONS_MANAGER, ADMIN -> true;
            default -> false;
        };
    }

    /** Xác định bốn vai bắt buộc được gán một chi nhánh trước thao tác theo chi nhánh, BR-803. */
    public boolean isBranchScoped() {
        return switch (this) {
            case BRANCH_STAFF, BRANCH_MANAGER, DELIVERY_STAFF, DRIVER -> true;
            default -> false;
        };
    }
}
