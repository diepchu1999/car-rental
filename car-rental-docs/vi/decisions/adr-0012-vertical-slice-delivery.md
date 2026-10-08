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

## Làm rõ 08/10/2026 — API trước, giao diện ở cuối mỗi lát cắt

Không bỏ lát cắt dọc. Mục này ghi lại cách đang làm thật, và chốt mốc giao diện — vì bản gốc ở trên
nói "xuyên toàn bộ các tầng … giao diện", trong khi từ Task 5 Tech Owner quyết làm API trước, kiểm
bằng Postman. Một ADR ghi khác cách làm thật sẽ dạy người đọc rằng các ADR khác cũng chỉ là gợi ý.

**1. Trong một lát cắt: API trước.** Mỗi module làm xong API thì kiểm bằng collection Postman import
được (`.ai/prompts/implement.md`, mục Postman). Ở bước này, "demo được" nghĩa là collection chạy được
bằng Collection Runner và mọi `pm.test` xanh.

**2. Giao diện ở cuối mỗi lát cắt, trước khi sang lát cắt sau.** Với lát cắt 1: sau khi xong
`payment`, làm `customer-web` cho luồng **tìm xe → đặt xe → trả cọc**, rồi mới sang lát cắt 2.

Lý do giữ mốc này: lý do gốc của ADR là **phát hiện hiểu sai nghiệp vụ sớm**. Postman kiểm "API trả
đúng không", không kiểm "khách có hiểu màn hình không". Ví dụ: kết quả tìm xe chưa có giá chỉ thật sự
lộ ra khi nhìn thấy một màn hình tìm xe không có giá. Làm API cho nhiều lát cắt rồi mới làm giao diện
là đúng loại rủi ro ADR này sinh ra để tránh.

Chưa chốt: phạm vi `admin-web` cho lát cắt 1 (duyệt xe, xác nhận cọc thủ công — ADR-0011), và thời
điểm làm mobile.

**3. `identity` làm trước `booking`, không phải trước cả lát cắt 1.** Hệ quả gốc ghi "nền tảng dùng
chung (`identity`…) phải làm trước lát cắt 1". Tìm xe không cần đăng nhập (`/api/v1/public`,
api-guideline §2), nên phần đầu lát cắt 1 làm được mà chưa có tài khoản. Đặt xe thì bắt buộc phải có
tài khoản và giấy phép lái xe đã xác minh (BR-106, BR-120).

Thứ tự còn lại của lát cắt 1: chốt BR-218 → `pricing` → `identity` → `booking` → `payment` →
`customer-web`.

## Hệ quả
- Nền tảng dùng chung (`identity`, hạ tầng local, tiện ích, test kiến trúc) phải làm trước lát cắt 1.
- Mỗi lát cắt kết thúc bằng thứ **demo được**, không phải bằng một tầng đã xong.
- Chấp nhận quay lại sửa các vùng đã làm khi lát cắt sau đòi hỏi.
