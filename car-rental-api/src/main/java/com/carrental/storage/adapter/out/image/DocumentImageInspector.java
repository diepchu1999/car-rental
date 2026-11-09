package com.carrental.storage.adapter.out.image;

import com.carrental.storage.application.port.out.InspectDocumentImagePort;
import com.carrental.storage.domain.DocumentImagePolicy;
import com.carrental.storage.domain.ImageMetadata;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.error.ErrorCode;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * BR-130: kiểm bytes, không dùng đuôi file/MIME client. Không OCR hoặc xác minh tính thật của giấy tờ.
 * JPEG/PNG đọc kích thước trước giải mã; HEIC kiểm container, cấu hình HEVC và các kích thước ispe,
 * không giải mã HEVC sang bitmap (JDK ImageIO không có codec HEIC).
 */
@Component
class DocumentImageInspector implements InspectDocumentImagePort {
    /** Kiểm byte trước mọi parser và chuyển lỗi parser sang mã chung, không log nội dung ảnh. */
    @Override public ImageMetadata inspect(byte[] bytes, DocumentImagePolicy policy) {
        if (bytes == null || bytes.length == 0) throw invalid();
        if (bytes.length > policy.maxBytes()) throw DomainException.ruleViolation(ErrorCode.DOCUMENT_IMAGE_TOO_LARGE);
        try {
            if (bytes.length >= 12 && fourcc(bytes, 4).equals("ftyp")) return inspectHeic(bytes, policy);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    String type = reader.getFormatName();
                    if (!type.equalsIgnoreCase("JPEG") && !type.equalsIgnoreCase("PNG")) throw invalid();
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    checkPixels(width, height, policy);
                    var image = reader.read(0);
                    if (image == null || image.getWidth() != width || image.getHeight() != height) throw invalid();
                    image.flush();
                    return new ImageMetadata(type.equalsIgnoreCase("PNG") ? "image/png" : "image/jpeg", width, height);
                } finally { reader.dispose(); }
            }
        } catch (DomainException known) { throw known;
        } catch (Exception malformed) { throw invalid(); }
    }

    /** Kiểm các box hữu hạn; không nhận AVIF/MP4 chỉ vì chúng cũng có box ftyp. */
    private static ImageMetadata inspectHeic(byte[] bytes, DocumentImagePolicy policy) {
        var state = new HeicStructure(policy);
        scanBoxes(bytes, 0, bytes.length, 0, state, "root");
        if (!state.hevcBrand || !state.meta || !state.media || !state.codec || !state.primary
                || !state.locations || !state.items || state.width == 0) throw invalid();
        return new ImageMetadata("image/heic", state.width, state.height);
    }

    /**
     * Đi qua đúng container chuẩn meta/iprp/ipco. Kiểm mọi ispe, không chỉ thumbnail cuối cùng.
     * Giới hạn độ sâu là hàng rào parser kỹ thuật, không phải giới hạn nghiệp vụ ảnh.
     */
    private static void scanBoxes(byte[] bytes, int start, int end, int depth, HeicStructure state, String parent) {
        if (depth > 6) throw invalid();
        for (int offset = start; offset < end;) {
            if (end - offset < 8) throw invalid();
            long length = unsignedInt(bytes, offset);
            String type = fourcc(bytes, offset + 4);
            int header = 8;
            if (length == 1) {
                if (end - offset < 16) throw invalid();
                length = ByteBuffer.wrap(bytes, offset + 8, 8).order(ByteOrder.BIG_ENDIAN).getLong();
                header = 16;
            } else if (length == 0) { length = end - offset; }
            if (length < header || length > end - offset) throw invalid();
            int payload = offset + header, next = offset + (int) length;
            switch (type) {
                case "ftyp" -> {
                    if (!parent.equals("root") || next - payload < 8 || (next - payload) % 4 != 0) throw invalid();
                    for (int pos = payload; pos < next; pos += 4) {
                        if (pos == payload + 4) continue; // minor_version không phải brand.
                        String brand = fourcc(bytes, pos);
                        if (brand.equals("heic") || brand.equals("heix")) state.hevcBrand = true;
                    }
                }
                case "meta" -> {
                    if (!parent.equals("root") || next - payload < 4) throw invalid();
                    state.meta = true;
                    scanBoxes(bytes, payload + 4, next, depth + 1, state, "meta");
                }
                case "iprp" -> {
                    if (!parent.equals("meta")) throw invalid();
                    scanBoxes(bytes, payload, next, depth + 1, state, "iprp");
                }
                case "ipco" -> {
                    if (!parent.equals("iprp")) throw invalid();
                    scanBoxes(bytes, payload, next, depth + 1, state, "ipco");
                }
                case "ispe" -> {
                    if (!parent.equals("ipco") || next - payload != 12) throw invalid();
                    long width = unsignedInt(bytes, payload + 4), height = unsignedInt(bytes, payload + 8);
                    if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) throw invalid();
                    checkPixels((int) width, (int) height, state.policy);
                    if (width * height > (long) state.width * state.height) {
                        state.width = (int) width; state.height = (int) height;
                    }
                }
                case "hvcC" -> {
                    if (!parent.equals("ipco") || next - payload < 23 || bytes[payload] != 1) throw invalid();
                    int arrays = bytes[payload + 22] & 255, cursor = payload + 23, units = 0;
                    for (int array = 0; array < arrays; array++) {
                        if (next - cursor < 3) throw invalid();
                        int count = ((bytes[cursor + 1] & 255) << 8) | (bytes[cursor + 2] & 255);
                        cursor += 3;
                        for (int unit = 0; unit < count; unit++) {
                            if (next - cursor < 2) throw invalid();
                            int size = ((bytes[cursor] & 255) << 8) | (bytes[cursor + 1] & 255);
                            cursor += 2;
                            if (size < 2 || size > next - cursor) throw invalid();
                            cursor += size; units++;
                        }
                    }
                    if (cursor != next || units == 0) throw invalid();
                    state.codec = true;
                }
                case "pitm" -> { if (parent.equals("meta") && next - payload >= 6) state.primary = true; }
                case "iloc" -> { if (parent.equals("meta") && next - payload >= 8) state.locations = true; }
                case "iinf" -> { if (parent.equals("meta") && next - payload >= 6) state.items = true; }
                case "mdat" -> { if (parent.equals("root") && next - payload > 4) state.media = true; }
                default -> { /* Box metadata khác giữ nguyên, không giải nén hoặc đưa vào log. */ }
            }
            offset = next;
        }
    }

    /** Đọc số nguyên unsigned mà không tràn khi nhân hai kích thước. */
    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    /** Đọc tên box cố định bốn byte, không dùng chuỗi lấy từ client làm MIME. */
    private static String fourcc(byte[] bytes, int offset) { return new String(bytes, offset, 4, StandardCharsets.US_ASCII); }

    /** Pixel vượt cấu hình bị chặn trước giải mã, kể cả trường hợp tích vượt miền int. */
    private static void checkPixels(int width, int height, DocumentImagePolicy policy) {
        if (width <= 0 || height <= 0 || (long) width * height > policy.maxPixels()) throw invalid();
    }

    /** Lỗi ảnh không kèm cause có thể chứa metadata hoặc nội dung nhạy cảm. */
    private static DomainException invalid() { return DomainException.ruleViolation(ErrorCode.DOCUMENT_IMAGE_INVALID); }

    /** Trạng thái parser cục bộ cho đúng một file, không chia sẻ giữa các request. */
    private static final class HeicStructure {
        final DocumentImagePolicy policy;
        boolean hevcBrand, meta, media, codec, primary, locations, items;
        int width, height;
        /** Gắn giới hạn của lượt kiểm hiện tại. */
        HeicStructure(DocumentImagePolicy policy) { this.policy = policy; }
    }
}
