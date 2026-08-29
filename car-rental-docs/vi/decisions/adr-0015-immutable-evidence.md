# ADR-0015: Hồ sơ bằng chứng bất biến, sống lâu hơn tài khoản

- Trạng thái: ACCEPTED · 27/08/2026

## Bối cảnh
Theo Nghị định 336/2025/NĐ-CP, thông báo phạt nguội gửi cho **chủ xe** — tức là công ty với mọi xe
công ty. Công ty chỉ **không bị xử phạt** nếu chứng minh được người vi phạm là khách thuê, bằng hợp
đồng, CCCD, giấy phép lái xe và biên bản bàn giao (BR-702).

Nghĩa là bộ hồ sơ đó không phải giấy tờ hành chính — **nó quyết định ai trả tiền phạt.**

## Quyết định

**1. Bất biến.** Biên bản bàn giao và giấy tờ đính kèm không sửa, không xoá, không ghi đè (BR-407).
Cần bổ sung thì thêm phiên bản mới kèm lý do; phiên bản cũ vẫn còn.

**2. Sống lâu hơn tài khoản.** Khách xoá tài khoản thì tài khoản ngừng hoạt động, nhưng **hồ sơ của
các đơn đã hoàn tất được giữ nguyên** (BR-123). Đây là nghĩa vụ pháp lý, không phải lựa chọn sản phẩm.

**3. Truy vấn được theo thời điểm.** Phải trả lời được "ai lái xe X lúc 14:32 ngày 3/5" với bất kỳ
thời điểm nào trong quá khứ, trong vài giây (BR-701). Với chuyến có tài xế, người chịu trách nhiệm là
**người lái được khai báo**, không phải người thuê (BR-703).

**4. Ảnh và giấy tờ ở object storage, CSDL chỉ giữ khoá.** Truy cập qua liên kết ký ngắn hạn, không
để công khai vĩnh viễn. Ảnh bàn giao **giữ nguyên mốc thời gian và toạ độ** — đó là phần giá trị
pháp lý của bằng chứng.

## Lý do
Mô hình khoá lịch của ADR-0005 vốn đã trả lời được yêu cầu 3 gần như miễn phí: một truy vấn tìm bản
ghi có khoảng thời gian chứa điểm cần tra. Thiết kế đúng cho bài toán trùng lịch hoá ra cũng là
thiết kế đúng cho bài toán bằng chứng.

## Hệ quả
- Không có thao tác xoá cứng trên các bảng bằng chứng. Chỉ thêm.
- Chính sách lưu trữ dữ liệu cá nhân phải nêu rõ ngoại lệ này cho khách biết trước.
- Cùng cơ chế phục vụ ba việc: phạt nguội (BR-701), xác định lỗi khi xe hỏng (BR-426), và khớp phiên
  sạc với đơn thuê (BR-417).
