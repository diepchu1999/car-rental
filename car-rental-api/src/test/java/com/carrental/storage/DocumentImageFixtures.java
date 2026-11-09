package com.carrental.storage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Ảnh giả không chứa giấy tờ; PNG/JPEG tự vẽ, HEIC nhỏ có nguồn/giấy phép cạnh tài nguyên. */
public final class DocumentImageFixtures {
    /** Lớp tiện ích không có trạng thái. */
    private DocumentImageFixtures() { }
    /** Sinh hình chữ nhật hai màu nhỏ, không dùng ảnh thật của bất cứ người nào. */
    public static byte[] image(String format) {
        try {
            var image = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 12; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, x < 8 ? 0x2471a3 : 0xf4d03f);
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) throw new IllegalStateException("Test image writer unavailable.");
            return output.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException("Cannot create fake test image.", failure); }
    }
    /** Đọc fixture HEIC thật 2×2, không giả kết quả kiểm ảnh bằng mock. */
    public static byte[] heic() {
        try (var input = DocumentImageFixtures.class.getResourceAsStream("/images/heic-2x2.base64")) {
            if (input == null) throw new IllegalStateException("Missing HEIC test fixture.");
            return Base64.getMimeDecoder().decode(input.readAllBytes());
        } catch (IOException failure) { throw new IllegalStateException("Cannot read HEIC fixture.", failure); }
    }
}
