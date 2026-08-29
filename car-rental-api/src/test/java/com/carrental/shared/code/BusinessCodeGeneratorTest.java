package com.carrental.shared.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm tra định dạng mã và cách sử dụng nguồn ngẫu nhiên.
 *
 * <p>Nguồn ngẫu nhiên kiểm soát được giúp test biết chính xác
 * kết quả mong đợi, không phụ thuộc may rủi.
 *
 * <p>Test không khởi động Spring hoặc kết nối CSDL.
 * Việc PostgreSQL chặn mã trùng được kiểm riêng ở phần persistence.
 */
class BusinessCodeGeneratorTest {

    /**
     * Kiểm tiền tố, dấu gạch nối và đúng sáu ký tự được chọn.
     *
     * <p>Các vị trí được chọn phủ đầu và cuối của cả nhóm chữ
     * lẫn nhóm số trong bảng ký tự.
     *
     * @param prefix tiền tố của chi nhánh hoặc xe
     */
    @ParameterizedTest
    @ValueSource(strings = {"CN", "XE"})
    void generatesExactCodeFromControlledValues(String prefix) {
        SequenceRandom random = new SequenceRandom(
                0, 25, 26, 35, 1, 24
        );
        BusinessCodeGenerator generator = new BusinessCodeGenerator(random);

        String code = generator.generate(prefix);

        assertEquals(prefix + "-AZ09BY", code);
        random.assertExhausted();
    }

    /**
     * Kiểm mỗi lần sinh mã sử dụng sáu giá trị tiếp theo,
     * không trả lại mã đã lưu từ lần gọi trước.
     */
    @Test
    void consumesFreshValuesForEachCall() {
        SequenceRandom random = new SequenceRandom(
                0, 1, 2, 3, 4, 5,
                26, 27, 28, 29, 30, 31
        );
        BusinessCodeGenerator generator = new BusinessCodeGenerator(random);

        assertEquals("CN-ABCDEF", generator.generate("CN"));
        assertEquals("CN-012345", generator.generate("CN"));

        random.assertExhausted();
    }

    /**
     * Kiểm ký tự và mã được phép lặp lại khi nguồn ngẫu nhiên lặp.
     *
     * <p>Bộ sinh mã không tự lưu lịch sử hoặc bảo đảm duy nhất.
     * Luồng ghi dữ liệu chịu trách nhiệm xử lý trùng mã dựa trên
     * ràng buộc UNIQUE của PostgreSQL.
     */
    @Test
    void permitsRepeatedCodesFromRepeatedRandomValues() {
        SequenceRandom random = new SequenceRandom(
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0
        );
        BusinessCodeGenerator generator = new BusinessCodeGenerator(random);

        assertEquals("CN-AAAAAA", generator.generate("CN"));
        assertEquals("CN-AAAAAA", generator.generate("CN"));

        random.assertExhausted();
    }

    /**
     * Kiểm constructor từ chối nguồn ngẫu nhiên null
     * với thông báo rõ tham số bị thiếu.
     */
    @Test
    void rejectsNullRandomGenerator() {
        NullPointerException exception = assertThrowsExactly(
                NullPointerException.class,
                () -> new BusinessCodeGenerator(null)
        );

        assertEquals(
                "random must not be null.",
                exception.getMessage()
        );
    }

    /**
     * Kiểm tiền tố sai bị từ chối trước khi lấy số ngẫu nhiên.
     *
     * <p>Không tự cắt khoảng trắng, đổi chữ thường thành chữ hoa
     * hoặc chấp nhận chữ ngoài phạm vi ASCII.
     *
     * @param prefix tiền tố không thỏa định dạng hai chữ cái ASCII viết hoa
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "\t\n",
            "C",
            "CNN",
            "cn",
            "Cn",
            "C1",
            "CN-",
            " CN",
            "CN ",
            "\u0106N"
    })
    void rejectsInvalidPrefixesWithoutUsingRandomness(String prefix) {
        SequenceRandom random = new SequenceRandom();
        BusinessCodeGenerator generator = new BusinessCodeGenerator(random);

        IllegalArgumentException exception = assertThrowsExactly(
                IllegalArgumentException.class,
                () -> generator.generate(prefix)
        );

        assertEquals(
                "Code prefix must contain exactly two uppercase ASCII letters.",
                exception.getMessage()
        );
        random.assertExhausted();
    }

    /**
     * Kiểm constructor mặc định tạo được mã đúng định dạng.
     *
     * <p>Không đoán nội dung ngẫu nhiên và không yêu cầu các mã
     * phải khác nhau, nên test không phụ thuộc xác suất trùng.
     *
     * @param prefix tiền tố của chi nhánh hoặc xe
     */
    @ParameterizedTest
    @ValueSource(strings = {"CN", "XE"})
    void defaultConstructorProducesExpectedFormat(String prefix) {
        BusinessCodeGenerator generator = new BusinessCodeGenerator();

        String code = generator.generate(prefix);

        assertTrue(
                code.matches(prefix + "-[A-Z0-9]{6}"),
                "Generated code must match the expected format."
        );
    }

    /**
     * Nguồn số dành riêng cho test, trả lần lượt các vị trí đã định.
     *
     * <p>Báo lỗi nếu code yêu cầu sai giới hạn, lấy quá số lần
     * dự kiến hoặc gọi phương thức ngẫu nhiên không được hỗ trợ.
     */
    private static final class SequenceRandom implements RandomGenerator {

        private final int[] indexes;
        private int position;

        /**
         * Sao chép dãy vị trí ký tự mà test muốn cung cấp.
         *
         * @param indexes các vị trí sẽ được trả theo thứ tự
         */
        private SequenceRandom(int... indexes) {
            this.indexes = indexes.clone();
        }

        /**
         * Trả vị trí tiếp theo trong dãy và kiểm hợp đồng lấy số.
         *
         * @param bound giới hạn trên không bao gồm của số cần lấy
         * @return vị trí ký tự tiếp theo đã được test định trước
         * @throws AssertionError nếu giới hạn, số lần gọi
         *                        hoặc dữ liệu mẫu không hợp lệ
         */
        @Override
        public int nextInt(int bound) {
            assertEquals(
                    36,
                    bound,
                    "All 36 alphabet characters must be available."
            );
            assertTrue(
                    position < indexes.length,
                    "Generator requested more random values than expected."
            );

            int value = indexes[position++];

            assertTrue(
                    value >= 0 && value < bound,
                    "Test random value must be within the requested bound."
            );

            return value;
        }

        /**
         * Từ chối phương thức nền mà test không sử dụng.
         *
         * <p>RandomGenerator yêu cầu hiện thực nextLong, nhưng
         * bộ sinh mã đang được kiểm phải gọi nextInt(bound).
         *
         * @return không trả về vì phương thức luôn báo lỗi
         * @throws AssertionError khi có lời gọi ngoài dự kiến
         */
        @Override
        public long nextLong() {
            throw new AssertionError(
                    "Only nextInt(bound) is expected."
            );
        }

        /**
         * Kiểm code đã dùng hết và đúng số giá trị được cung cấp,
         * tránh bỏ sót trường hợp lấy ít số hơn dự kiến.
         */
        private void assertExhausted() {
            assertEquals(
                    indexes.length,
                    position,
                    "Generator must consume every supplied random value."
            );
        }
    }
}