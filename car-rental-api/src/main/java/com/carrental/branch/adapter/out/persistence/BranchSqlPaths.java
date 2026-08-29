package com.carrental.branch.adapter.out.persistence;

/**
 * Tập trung đường dẫn các tài nguyên SQL của persistence adapter chi nhánh.
 *
 * <p>Đường dẫn tính từ gốc classpath, không phải đường dẫn
 * tuyệt đối trên máy và không chứa tiền tố src/main/resources.
 */
final class BranchSqlPaths {

    /**
     * Đường dẫn câu lệnh chèn chi nhánh mới.
     */
    static final String INSERT = "sql/branch/insert_branch.sql";

    /**
     * Đường dẫn câu truy vấn chi tiết chi nhánh theo mã nghiệp vụ.
     */
    static final String FIND_BY_CODE =
            "sql/branch/find_branch_by_code.sql";

    /**
     * Ngăn khởi tạo đối tượng vì lớp chỉ cung cấp các hằng đường dẫn.
     */
    private BranchSqlPaths() {
    }
}