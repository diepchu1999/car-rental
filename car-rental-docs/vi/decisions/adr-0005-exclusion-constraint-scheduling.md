# ADR-0005: Chống trùng lịch bằng ràng buộc loại trừ của PostgreSQL

- Trạng thái: ACCEPTED · 27/08/2026 · Chief Architect đề xuất, Tech Owner duyệt
- **Mức quan trọng: cao nhất trong toàn bộ hệ thống**

## Bối cảnh
BR-104: không hai khoá lịch nào được chồng thời gian trên cùng một xe. BR-507: một tài xế không có
hai phân công chồng giờ. Nhân viên giao xe cũng vậy.

Đây là tài nguyên **vật lý, không nhân bản được**. Nhận hai đơn trên một xe nghĩa là chắc chắn một
khách bị huỷ — loại lỗi không sửa được bằng lời xin lỗi.

## Các cách đã cân nhắc
| Cách | Vì sao không đủ |
|---|---|
| Đọc kiểm tra rồi ghi | **Luôn sai** dưới đồng thời: hai transaction cùng đọc thấy trống, cùng ghi |
| Khoá Redis | Giảm xác suất, không loại trừ. Hỏng khi khoá hết hạn sớm hoặc tiến trình chết giữa transaction |
| Khoá bi quan trên hàng xe | Chạy được nhưng nối tiếp hoá mọi thao tác trên một xe, và vẫn cần code đúng ở mọi lối vào |

## Quyết định
Đặt ràng buộc ở tầng CSDL — nơi duy nhất bảo đảm được trong mọi tình huống:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE availability.reservation
  ADD CONSTRAINT reservation_no_overlap
  EXCLUDE USING gist (
      vehicle_id WITH =,
      period     WITH &&
  ) WHERE (status IN ('HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED'));
```

Áp dụng cùng cơ chế cho `dispatch.driver_assignment` (theo `driver_id`) và phân công nhân viên
giao xe.

## Ba điểm tinh tế bắt buộc phải giữ

**1. Mọi lý do bận nằm chung một bảng.** Đơn thuê, giữ chỗ, bảo dưỡng, đăng kiểm, điều chuyển chi
nhánh, chủ xe tự khoá — tất cả là `reservation` với `kind` khác nhau. Tách bảng riêng cho bảo dưỡng
thì ràng buộc không nhìn thấy nó, và hệ thống sẽ cho khách đặt thuê đè lên ngày xe nằm gara.

**2. `period` đã bao gồm khoảng đệm.** BR-109 và BR-116: 2 giờ cho gói ngày, 1 giờ cho gói giờ.
Khoảng đệm được cộng vào khi tạo bản ghi, **không phải** khi hiển thị. Nó nằm bên trong ràng buộc.

**3. `HELD` bắt buộc có hạn.** BR-103: 1 tiếng. Ép bằng `CHECK`, và có job dọn bản quá hạn. Không có
hạn thì xe bị khoá vĩnh viễn bởi những đơn khách bỏ dở.

## Làm rõ 02/10/2026 — ba điều Task 5 phát hiện

Không đổi quyết định: ràng buộc loại trừ vẫn là cơ chế **duy nhất** chống trùng lịch. Đây là ba chỗ bản
gốc ở trên nói thiếu hoặc sai.

**1. `COMPLETED` nằm trong mệnh đề `WHERE`.** Câu SQL ở mục Quyết định thiếu nó. Đúng là
`status IN ('HELD', 'CONFIRMED', 'IN_USE', 'BLOCKED', 'COMPLETED')` — để khoảng đệm sau khi trả xe vẫn
chặn. Ví dụ và lý do ở `database-guideline.md` §4; V004 đã dùng bản đúng.

**2. Hệ quả của điểm 1: khoảng không chặn trên không bao giờ được sang `COMPLETED`.** Nếu không, xe bị
khoá vĩnh viễn. Khoá do giấy tờ (BR-015) chỉ được dời điểm bắt đầu. Ép bằng `chk_compliance_open_blocked`
(V005). Lỗi này lọt qua tài liệu (status-flow §2), không phải qua code — review vòng 2 bắt được.

**3. Ràng buộc loại trừ có thể gây deadlock.** Ràng buộc được kiểm **sau khi** ghi, nên hai câu ghi chồng
lịch chạy cùng lúc có thể chờ nhau. Đo trên PostgreSQL 17.5 với đúng schema V004:

| Tình huống | Kết quả |
|---|---|
| 2 luồng `INSERT` chồng lịch, 12 giây | 10 deadlock / 8.703 giao dịch |
| Như trên, bỏ `ON CONFLICT` | 10 deadlock / 12.719 — `ON CONFLICT` không phải nguyên nhân |
| Như trên, bọc savepoint + thử lại `40P01` | 0 / 15.722 |
| `UPDATE` chỉ đổi `status` song song với `INSERT` chồng lịch | 7 deadlock / 56 lần đổi trạng thái |

Bất biến **không thủng** — PostgreSQL huỷ một bên, chỉ một bên commit. Cái hỏng là bên thua nhận 500
thay vì 409. Quyết định bổ sung: mọi câu ghi vào bảng có ràng buộc loại trừ chạy trong savepoint, thử lại
`40P01` tối đa 3 lần, ở persistence adapter (`backend-guideline.md` §3). Áp cho cả bảng phân công tài xế
và nhân viên giao xe.

Đã cân nhắc và loại: đổi `40P01` thành "xe đã bận" (sai khi bên kia rollback), thử lại cả transaction ở
ranh giới (mọi bên gọi phải biết), advisory lock theo xe (cơ chế thứ hai, và chính nó deadlock khi đổi xe
giữ cọc khoá hai xe cùng lúc).

## Hệ quả
- Chỉ vùng `availability` được ghi vào bảng này. Khoá bằng test kiến trúc.
- Adapter bắt vi phạm ràng buộc và dịch thành lỗi nghiệp vụ → HTTP 409. Đây là **ngoại lệ có chủ
  đích** của quy tắc "kiểm tra nghiệp vụ ở service": chỉ CSDL biết sự thật này.
- 409 là **tình huống bình thường** của hệ thống phân bổ tài nguyên khan hiếm, không phải sự cố —
  giao diện phải xử lý tử tế.
- Bắt buộc test đồng thời thật (nhiều luồng, PostgreSQL thật). Test tuần tự không chứng minh gì.
