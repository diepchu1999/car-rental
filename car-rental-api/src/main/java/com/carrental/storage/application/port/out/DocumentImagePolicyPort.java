package com.carrental.storage.application.port.out;

import com.carrental.storage.domain.DocumentImagePolicy;
import java.time.Instant;

/** Nguồn cấu hình BR-130, nhận mốc thời gian theo ADR-0013. */
public interface DocumentImagePolicyPort {
    /** Lấy bộ giới hạn tại thời điểm xử lý; adapter local chưa có lịch sử hiệu lực. */
    DocumentImagePolicy at(Instant at);
}
