# ADR-0012: Xây theo lát cắt dọc

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Quyết định
Làm trọn từng luồng nghiệp vụ xuyên toàn bộ các tầng — migration, domain, use case, API, giao diện —
rồi mới sang luồng sau. Không xây hết backend rồi mới tới frontend.

Thứ tự lát cắt đề xuất:
1. **Tìm xe → đặt xe → giữ chỗ → trả cọc** — chạm ngay `availability`, tức là rủi ro số một (ADR-0005).
2. **Bàn giao → dùng xe → trả xe → quyết toán** — chạm bằng chứng và ba khoản tiền.
3. **Huỷ đơn và đổi xe giữ cọc** — nhánh phức tạp nhất về tiền.
4. **Thuê có tài xế** — thêm trục tài nguyên tranh chấp thứ hai.
5. **Thuê dài hạn** — kỳ thanh toán, thu hồi xe.
6. Vận hành đội xe, phạt nguội, sự cố, đánh giá.

## Lý do
Rủi ro lớn nhất của dự án này không phải hiệu năng mà là **hiểu sai nghiệp vụ**. 149 quy tắc được
soạn từ phỏng vấn, chưa qua va chạm với người dùng thật và chưa qua va chạm với chính chiếc xe.

Lát cắt dọc cho thứ chạy được sớm, nên phát hiện sai sớm. Xây backend trước nghĩa là nhiều tháng
không có gì nhìn thấy được, và mọi hiểu sai lộ ra ở thời điểm đắt nhất.

Lát cắt đầu tiên cố tình chọn phần khó nhất: nếu cách chống trùng lịch có bất ngờ gì, ta muốn biết
ở tuần đầu chứ không phải tháng thứ ba khi năm vùng khác đã xây đè lên.

## Hệ quả
- Nền tảng dùng chung (`identity`, hạ tầng local, tiện ích, test kiến trúc) phải làm trước lát cắt 1.
- Mỗi lát cắt kết thúc bằng thứ **demo được**, không phải bằng một tầng đã xong.
- Chấp nhận quay lại sửa các vùng đã làm khi lát cắt sau đòi hỏi.
