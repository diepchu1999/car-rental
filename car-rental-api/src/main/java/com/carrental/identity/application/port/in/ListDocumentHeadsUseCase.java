package com.carrental.identity.application.port.in;

import com.carrental.identity.application.view.DocumentHeadSummary;
import java.util.List;

/** BR-130: khách theo dõi bản nộp mới nhất và bản VERIFIED được chọn cho từng loại giấy tờ. */
public interface ListDocumentHeadsUseCase {
    /** Chưa nộp trả danh sách rỗng; không nhận mã tài khoản hoặc quyền do client tự khai. */
    List<DocumentHeadSummary> list();
}
