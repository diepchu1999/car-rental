package com.carrental.identity.application.port.out;

import com.carrental.identity.domain.Account;
import java.time.Instant;

/** Cổng ghi tài khoản; application sở hữu transaction, mã nghiệp vụ và thời điểm tạo. */
public interface WriteAccountPort {
    /**
     * Thử chèn trực tiếp, để UNIQUE quyết định xung đột (BR-120, ADR-0006).
     * Khi subject đã có, bên gọi phải đọc lại và đối chiếu số/consent, không dùng aggregate mới.
     * Chỉ CODE_COLLISION cho phép sinh lại mã; số thuộc subject khác gây ACCOUNT_IDENTITY_CONFLICT.
     * Transaction bên gọi dùng READ_COMMITTED để lần đọc sau thấy transaction thắng đã commit.
     * Kết quả INSERTED chưa đồng nghĩa transaction ngoài đã commit.
     */
    InsertResult insert(Account account, Instant createdAt);

    /**
     * Bổ sung bằng chứng đầu tiên theo BR-120, không đổi định danh hoặc ghi đè consent đã có.
     * Aggregate phải có consent. Trả true khi cập nhật đúng một dòng; false khi không khớp
     * định danh hoặc consent đã tồn tại. Bên gọi phải đọc lại thay vì giả định giá trị mình thắng.
     */
    boolean addConsentIfAbsent(Account account);

    /** Phân biệt ba kết quả để service không thử lại mã cho lỗi danh tính. */
    enum InsertResult {
        /** Đã chèn trong transaction hiện tại. */
        INSERTED,
        /** Subject đã có, cần lấy tài khoản hiện hữu. */
        SUBJECT_EXISTS,
        /** Chỉ mã nghiệp vụ bị trùng, có thể sinh mã khác. */
        CODE_COLLISION
    }
}
