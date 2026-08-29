# API Guideline

> Nền: ADR-0009 (React Native — ràng buộc versioning) · ADR-0010 (hai web app).

## 1. Phong cách
REST, JSON. Danh từ số nhiều: `/vehicles` `/bookings` `/quotes` `/payments` `/handovers`
`/reservations` `/contracts` `/incidents` `/reviews`.

## 2. Namespace theo audience

| Namespace | Ai dùng | Client |
|---|---|---|
| `/api/v1/public/**` | Không cần đăng nhập — tìm xe, xem chi tiết | `customer-web`, mobile |
| `/api/v1/client/**` | Khách thuê | `customer-web` **và mobile** |
| `/api/v1/admin/**` | Vận hành, kế toán, quản trị | `admin-web` |

**Không tạo `/api/v1/mobile/**`.** Mobile dùng chung `client`. Hai namespace cho cùng một nghiệp vụ
nghĩa là hai lần sửa cho mỗi thay đổi rule, và chúng sẽ lệch nhau. Khác biệt thật của mobile —
chụp ảnh, vị trí, đăng ký push token — là **endpoint bổ sung**, không phải bản sao.

`/api/v1/host/**` thêm ở GĐ2.

## 3. Cấu trúc response

```json
{ "success": true, "data": {}, "error": null }
```
```json
{ "success": false, "data": null,
  "error": { "code": "VEHICLE_NOT_AVAILABLE",
             "message": "Xe vừa được người khác đặt cho khoảng thời gian này" } }
```

`error.code` là **hợp đồng ổn định** — mobile bản cũ hiển thị thông báo dựa vào nó.
Danh mục mã lỗi ở [`backend-guideline.md`](backend-guideline.md) §5.

## 4. Versioning — ràng buộc do mobile áp đặt

Web deploy xong là mọi người dùng bản mới ngay. **Mobile thì không** — người dùng cập nhật khi họ
muốn, có người nhiều tháng không cập nhật. Backend luôn phải phục vụ đồng thời nhiều phiên bản client.

Trong cùng một `v1`:

| | |
|---|---|
| ✅ Được | Thêm field vào response · thêm endpoint · thêm field optional vào request |
| ❌ Không | Xoá field khỏi response, kể cả field "không ai dùng" |
| ❌ Không | Đổi kiểu dữ liệu hoặc đổi nghĩa của field đã có |
| ❌ Không | Thêm field bắt buộc vào request |
| ⚠️ Cẩn thận | Thêm giá trị enum mới — client phải bỏ qua giá trị lạ thay vì hỏng, và điều đó phải đúng **từ bản mobile đầu tiên** |

Thay đổi phá hợp đồng ⇒ `/api/v2/**`, `v1` sống song song tới khi số người dùng bản cũ đủ nhỏ.

Mọi request nên kèm:
```text
X-Client-Platform: ios | android | web
X-Client-Version: 1.4.2
```
Không có hai header này thì không trả lời được câu "còn bao nhiêu người dùng bản cũ" trước khi tắt `v1`.

## 5. Idempotency

`Idempotency-Key` **bắt buộc** cho: tạo đơn · mọi thao tác thanh toán · hoàn tiền · chuyển cọc khi
đổi xe · xác nhận bàn giao · gia hạn chuyến.

Mạng di động rớt giữa chừng là chuyện thường và client sẽ thử lại. Không có key thì thử lại nghĩa là
đặt hai đơn hoặc thu tiền hai lần.

## 6. Mã HTTP

- 200 · 201 · 204
- 400 lỗi định dạng · 401 chưa xác thực · 403 bị cấm · 404 không tìm thấy
- **409 xung đột tranh chấp** — xe vừa bị đặt, tài xế vừa bị phân công, xác nhận lại khoản đã xác nhận
- 422 hợp lệ về định dạng nhưng vi phạm quy tắc nghiệp vụ
- 429 quá nhiều request · 500 lỗi bất ngờ

409 quan trọng trong hệ thống này hơn hầu hết hệ thống khác. Nó là **tình huống bình thường** của
việc phân bổ tài nguyên khan hiếm, không phải sự cố — client phải xử lý tử tế.

## 7. Báo giá là resource riêng

Không tính giá ngầm bên trong lệnh tạo đơn:

```text
POST /api/v1/client/quotes     → quoteCode + bảng chi tiết + hạn dùng
POST /api/v1/client/bookings   → body chứa quoteCode
```

Khách phải thấy chính xác số tiền **trước khi** cam kết, và số đó không được đổi giữa lúc xem và lúc
bấm. Báo giá bất biến, có hạn; hết hạn thì `QUOTE_EXPIRED`.

Server **luôn tính lại** từ `quoteCode`. Số tiền client gửi chỉ để đối chiếu; lệch thì
`PAYMENT_AMOUNT_MISMATCH` (BR-216).

Báo giá phải trả về **bảng chi tiết tách dòng**: tiền thuê theo từng ngày loại gì, phí tài xế, phí
giao xe, **phí bảo hiểm dòng riêng** (BR-231), giảm giá, tổng. Minh bạch là yêu cầu nghiệp vụ, không
phải tuỳ chọn giao diện.

## 8. Phân trang
Cursor cho danh sách lớn (kết quả tìm xe, lịch sử đơn). Offset chấp nhận được cho danh sách admin nhỏ.
**Mobile bắt buộc cursor** — cuộn vô hạn bằng offset sẽ trùng hoặc sót bản ghi khi dữ liệu thay đổi.

## 9. Danh mục endpoint

Danh mục endpoint được lưu trong `../api/`, theo từng module.

- [API chi nhánh — admin](../api/branch.md)
- [API xe — admin](../api/vehicle.md)

Mọi thay đổi endpoint phải cập nhật danh mục trong **cùng** một thay đổi,
theo khóa `HTTP method + path`.
