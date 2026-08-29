package com.carrental.shared.code;

import java.util.random.RandomGenerator;

/**
 * Cho phép test ở package khác cung cấp nguồn số cho bộ sinh mã.
 *
 * <p>Factory nằm cùng package với BusinessCodeGenerator nên gọi được
 * constructor package-private mà không thay đổi code production.
 *
 * <p>Lớp chỉ thuộc test source, không được đóng gói vào ứng dụng.
 */
public final class BusinessCodeGeneratorTestFactory {

    /**
     * Ngăn tạo đối tượng vì factory chỉ cung cấp phương thức static.
     */
    private BusinessCodeGeneratorTestFactory() {
    }

    /**
     * Tạo bộ sinh mã thật sử dụng nguồn số do test kiểm soát.
     *
     * @param random nguồn số dành cho kịch bản kiểm thử
     * @return bộ sinh mã sử dụng nguồn số được cung cấp
     * @throws NullPointerException nếu nguồn số là null
     */
    public static BusinessCodeGenerator create(RandomGenerator random) {
        return new BusinessCodeGenerator(random);
    }
}