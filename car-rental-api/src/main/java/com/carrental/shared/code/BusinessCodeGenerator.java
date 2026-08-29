package com.carrental.shared.code;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Sinh mã nghiệp vụ gồm tiền tố, dấu gạch nối và sáu ký tự ngẫu nhiên.
 *
 * <p>Application service chọn tiền tố phù hợp với tài nguyên:
 * CN cho chi nhánh và XE cho xe, theo database-guideline mục 2.
 *
 * <p>Lớp chỉ sinh mã, không truy cập CSDL và không bảo đảm mã
 * chưa từng tồn tại. Tính duy nhất phải được ép bằng ràng buộc
 * UNIQUE và cơ chế thử lại khi trùng mã ở luồng ghi dữ liệu.
 *
 * <p>Mã nghiệp vụ không phải thông tin xác thực và không thay thế
 * kiểm tra quyền truy cập tài nguyên.
 */
@Component
public final class BusinessCodeGenerator {

    private static final int SUFFIX_LENGTH = 6;

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final Pattern PREFIX_PATTERN =
            Pattern.compile("[A-Z]{2}");

    private final RandomGenerator random;

    /**
     * Khởi tạo bộ sinh mã dùng SecureRandom cho ứng dụng.
     *
     * <p>Spring sử dụng constructor không tham số này.
     * Nguồn ngẫu nhiên được tạo một lần và dùng lại giữa các lần sinh mã.
     */
    public BusinessCodeGenerator() {
        this(new SecureRandom());
    }

    /**
     * Khởi tạo bộ sinh mã với nguồn ngẫu nhiên được cung cấp.
     *
     * <p>Constructor chỉ truy cập được trong cùng package để test
     * có thể kiểm soát chuỗi số ngẫu nhiên và kiểm kết quả chính xác.
     * Không cần đăng ký RandomGenerator thành Spring bean.
     *
     * @param random nguồn cung cấp số ngẫu nhiên
     * @throws NullPointerException nếu nguồn ngẫu nhiên là null
     */
    BusinessCodeGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(
                random,
                "random must not be null."
        );
    }

    /**
     * Sinh mã có tiền tố được chỉ định và sáu ký tự thuộc A-Z hoặc 0-9.
     *
     * <p>Tiền tố là hằng nội bộ của application service, không lấy
     * trực tiếp từ request. Truyền CN hoặc XE, không kèm dấu gạch nối.
     *
     * <p>Phương thức không kiểm tra mã đã tồn tại trong CSDL.
     *
     * @param prefix tiền tố gồm đúng hai chữ cái ASCII viết hoa
     * @return mã theo định dạng tiền tố, dấu gạch nối và sáu ký tự
     * @throws IllegalArgumentException nếu tiền tố không hợp lệ
     */
    public String generate(String prefix) {
        if (prefix == null || !PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                    "Code prefix must contain exactly two uppercase ASCII letters."
            );
        }

        StringBuilder code = new StringBuilder(prefix);
        code.append('-');

        for (int index = 0; index < SUFFIX_LENGTH; index++) {
            int characterIndex = random.nextInt(ALPHABET.length());
            code.append(ALPHABET.charAt(characterIndex));
        }

        return code.toString();
    }
}