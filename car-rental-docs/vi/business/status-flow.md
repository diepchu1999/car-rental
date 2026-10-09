# Status Flow — Máy trạng thái

> Trạng thái và các bước chuyển của từng thực thể chính. Dựa trên 145 quy tắc trong
> [`business-rules.md`](business-rules.md).
>
> **Quy tắc chung cho mọi máy trạng thái trong tài liệu này:**
> 1. Không nhảy cóc — mọi chuyển trạng thái đi qua đúng một cạnh có trong sơ đồ.
> 2. **Lịch sử chuyển trạng thái** — bắt buộc ghi đầy đủ (từ đâu, tới đâu, ai, khi nào, lý do,
>    không xoá) với những thực thể có **giá trị pháp lý hoặc tiền**: đơn thuê, ba khoản tiền,
>    hợp đồng dài hạn, sự cố. Với các thực thể còn lại — như khoá lịch — chỉ cần mốc thời gian
>    đổi trạng thái gần nhất.
>
>    *(Thu hẹp 22/09/2026: bản gốc đòi lịch sử cho mọi máy trạng thái. Với khoá lịch thì bản thân
>    bảng đã là lịch sử — mỗi lượt là một bản ghi — nên một bảng lịch sử nữa chỉ là trùng lặp.)*
> 3. Trạng thái cuối là cuối — không quay lại.

---

## 1. Đơn thuê — `Booking`

```text
                    ┌──────────────────┐
                    │  PENDING_PAYMENT │  đã giữ chỗ · hạn 1 tiếng
                    └───┬────┬─────┬───┘
         hết hạn / huỷ  │    │     │ trả cọc xong
                        │    │     ▼
                        │    │  ┌──────────────┐
                        │    │  │  CONFIRMED   │  chỗ chuyển HELD → CONFIRMED
                        │    │  └──┬───┬───┬───┘
                        │    │     │   │   │ bàn giao xong
                        │    │     │   │   ▼
                        │    │     │   │  ┌──────────────┐
                        │    │     │   │  │ IN_PROGRESS  │  khách đang giữ xe
                        │    │     │   │  └──────┬───────┘
                        │    │     │   │         │ trả xe (bình thường
                        │    │     │   │         │ hoặc kết thúc sớm)
                        │    │     │   │         ▼
                        │    │     │   │  ┌──────────────┐
                        │    │     │   │  │   RETURNED   │  chờ quyết toán
                        │    │     │   │  └──────┬───────┘
                        │    │     │   │         │ quyết toán xong,
                        │    │     │   │         │ hoàn thế chấp
                        ▼    ▼     ▼   ▼         ▼
                  ┌──────────┐ ┌─────────┐ ┌───────────┐
                  │CANCELLED │ │ NO_SHOW │ │ COMPLETED │
                  └──────────┘ └─────────┘ └───────────┘
                        ▲
                        │ (nhánh riêng)
                  ┌───────────┐
                  │ CONVERTED │  đã đổi sang đơn khác — KHÔNG phải huỷ
                  └───────────┘
```

### Bảng chuyển trạng thái

| Từ → Đến | Ai kích hoạt | Điều kiện | Quy tắc |
|---|---|---|---|
| *(tạo)* → `PENDING_PAYMENT` | Khách | Báo giá còn hạn · giữ chỗ thành công · trong giới hạn đặt trước 1–3 giờ / 6 tháng | BR-102, BR-121, BR-122 |
| `PENDING_PAYMENT` → `CANCELLED` | Hệ thống | Quá 1 tiếng chưa trả cọc | BR-103 |
| `PENDING_PAYMENT` → `CANCELLED` | Khách | Khách chủ động huỷ — hoàn 100% vì chưa trả cọc | BR-301 |
| `PENDING_PAYMENT` → `CONVERTED` | Nhân viên | Khách đồng ý đổi xe khác | BR-305 → BR-309 |
| `PENDING_PAYMENT` → `CONFIRMED` | Hệ thống | Cọc đã được xác nhận | BR-201, BR-213 |
| `CONFIRMED` → `CANCELLED` | Khách | Theo thang ba mốc | BR-301 |
| `CONFIRMED` → `CANCELLED` | Công ty | `BRANCH_MANAGER` duyệt + lý do · hoàn 100% | BR-303 |
| `CONFIRMED` → `CANCELLED` | Đối tác *(GĐ2)* | Trừ sao + bồi thường 40% · khách hoàn 100% | BR-302 |
| `CONFIRMED` → `CANCELLED` | Công ty | Không có tài xế thay được | BR-508 |
| `CONFIRMED` → `CONVERTED` | Nhân viên | Khách đồng ý đổi xe | BR-305 |
| `CONFIRMED` → `NO_SHOW` | Hệ thống | Quá giờ hẹn 2 giờ, đã gọi điện, không liên lạc được | BR-111 |
| `CONFIRMED` → `IN_PROGRESS` | Nhân viên/chủ xe | **Bốn điều kiện đồng thời** — xem dưới | BR-204, BR-205, BR-502 |
| `IN_PROGRESS` → `RETURNED` | Nhân viên | Xe đã về, ký biên bản nhận lại | BR-407 |
| `IN_PROGRESS` → `RETURNED` | Nhân viên | Kết thúc sớm do xe hỏng | BR-425 |
| `RETURNED` → `COMPLETED` | Hệ thống | Quyết toán xong, thế chấp đã hoàn | BR-212, BR-418 |

### Điều kiện để chuyển sang `IN_PROGRESS`

Bốn điều kiện phải đúng **đồng thời**, thiếu một là không khởi hành được:

1. **Phần còn lại đã thanh toán** và chủ xe/nhân viên xác nhận trên app — BR-204
2. **Khách xác nhận** đã thanh toán đủ, ghi trên biên bản bàn giao — BR-205
3. **Biên bản bàn giao đã ký hai bên**: ảnh, ODO, nhiên liệu, mốc thời gian — BR-407
4. Nếu là chuyến **có tài xế**: đã có **người lái được khai báo và xác minh GPLX** — BR-502

Điều kiện 4 là ràng buộc pháp lý, không phải tiện ích: xảy ra chuyện mà không xác định được ai lái
thì công ty chịu (BR-701, BR-703).

Với thuê **tự lái**, thêm điều kiện: người nhận xe khớp giấy tờ trong đơn — BR-107.

### Bất biến của `Booking`

| Bất biến | Quy tắc |
|---|---|
| `CONFIRMED` **luôn** ứng với một khoá lịch `CONFIRMED` — chuyển trong cùng một transaction | BR-104 |
| Huỷ đơn **luôn** giải phóng khoá lịch. Không ngoại lệ | BR-304 |
| `CONVERTED` **không phải** `CANCELLED` — báo cáo tỷ lệ huỷ không đếm nó | BR-307 |
| Đơn đóng băng giá, hoa hồng, và mọi tham số cấu hình tại thời điểm tạo | BR-122, BR-208, BR-225 |
| `COMPLETED` và `CANCELLED` là trạng thái cuối | — |

---

## 2. Khoá lịch xe — `Reservation`

Mọi lý do khiến xe bận đều dùng chung máy trạng thái này.

```text
   (đơn thuê)                          (bảo dưỡng · đăng kiểm ·
        │                               điều chuyển · chủ xe khoá)
        ▼                                        │
   ┌─────────┐  hết hạn 1 tiếng   ┌──────────┐   │
   │  HELD   │───────────────────►│ RELEASED │   │
   └────┬────┘                    └──────────┘   │
        │ cọc đã trả                   ▲         │
        ▼                              │ đơn huỷ ▼
   ┌───────────┐──────────────────────┘    ┌───────────┐
   │ CONFIRMED │                            │  BLOCKED  │
   └─────┬─────┘                            └─────┬─────┘
         │ bàn giao                               │ xong việc
         ▼                                        ▼
   ┌────────────┐   trả xe    ┌───────────┐  ┌───────────┐
   │   IN_USE   │────────────►│ COMPLETED │  │ COMPLETED │
   └────────────┘             └───────────┘  └───────────┘
```

Khoá do giấy tờ hết hạn (`COMPLIANCE_HOLD`) có máy trạng thái **riêng** — nó không đi đường "xong việc":

```text
   (giấy tờ hết hạn ngày D)
        │
        ▼
   ┌──────────────┐   gia hạn giấy tờ: dời điểm bắt đầu tới hạn mới (BR-015)
   │   BLOCKED    │──┐
   │   [D, ∞)     │◄─┘
   └──────────────┘
   Không có cạnh ra: không bao giờ COMPLETED, không bao giờ RELEASED.
```

**Vì sao không được hoàn tất khoá giấy tờ:** `COMPLETED` vẫn chặn (để giữ khoảng đệm của đơn thuê —
database-guideline §4), mà khoảng này không có chặn trên. Hoàn tất nó nghĩa là khoá xe **vĩnh viễn**,
và xe lặng lẽ biến mất khỏi tìm kiếm. Bản trước của sơ đồ này vẽ "giấy tờ hết hạn" đi chung đường
`BLOCKED → COMPLETED` — sai, phát hiện ở review vòng 2 Task 5.

**Khoá vận hành xong sớm hoặc tạo nhầm:** `BLOCKED → COMPLETED` vẫn chặn tới hết khoảng đã đặt, và
hiện không có cách huỷ một khoá. Có nên nhả phần còn lại hay không — **chưa chốt** (business-rules.md,
mục "Quy tắc chưa chốt").

| Bất biến | Quy tắc |
|---|---|
| Không hai khoá lịch nào chồng thời gian trên cùng một xe | BR-104 |
| `HELD` **bắt buộc** có hạn 1 tiếng; quá hạn tự chuyển `RELEASED` | BR-103 |
| Khoảng đệm (2 giờ / 1 giờ gói giờ) nằm **bên trong** khoảng khoá | BR-109, BR-116 |
| `BLOCKED` đi thẳng vào trạng thái chặn, không qua `HELD` | BR-011, BR-012, BR-007 |
| Khoá do giấy tờ hết hạn là khoảng **không chặn trên**, gia hạn thì dời điểm bắt đầu | BR-015 |
| Khoá do giấy tờ hết hạn **không bao giờ** sang `COMPLETED` hay `RELEASED` — chỉ dời điểm bắt đầu | BR-015, BR-012 |
| Mỗi xe tối đa **một** khoá do giấy tờ; điểm bắt đầu là hạn **sớm nhất** trong các giấy tờ bắt buộc | BR-015 |
| Chỉ vùng `availability` được tạo và đổi trạng thái khoá lịch | domain-map §4 |

---

## 3. Ba khoản tiền

Ba khoản có vòng đời **khác nhau**, không được gộp (glossary §1).

### 3.1. Cọc — `deposit`

```text
PENDING ──► PAID ──► APPLIED          (trừ vào tiền thuê khi hoàn tất)
   │          │
   │          ├──► REFUNDED_FULL      (huỷ >1 tuần · công ty/đối tác huỷ)
   │          ├──► REFUNDED_PARTIAL   (huỷ 1 tuần–24h: hoàn 60%)
   │          ├──► FORFEITED          (huỷ <24h · NO_SHOW)
   │          └──► TRANSFERRED        (đổi xe: chuyển sang đơn mới)
   ▼
FAILED
```
BR-201, BR-202, BR-301, BR-306

### 3.2. Phần còn lại — `balance`

```text
PENDING ──► PAID
```
Xác nhận **hai phía** (BR-204 + BR-205) mới được coi là `PAID`, và chỉ khi đó chuyến mới khởi hành.

### 3.3. Thế chấp — `collateral`

```text
RECEIVED ──► RETURNED          (hoàn 100% ngay sau khi trả xe)
    │
    └──────► DEDUCTED ──► RETURNED_REMAINDER
             (trừ bù kỳ thanh toán quá hạn khi đình chỉ hợp đồng)
```
BR-209 → BR-212, BR-221

> **Thế chấp không bao giờ chuyển thành doanh thu.** Nó là khoản phải trả — tài sản giữ hộ.
> Trường hợp `DEDUCTED` là bù thiệt hại có căn cứ, không phải doanh thu bán hàng.

---

## 4. Xe — `Vehicle`

```text
DRAFT ──► PENDING_APPROVAL ──► ACTIVE ◄──► INACTIVE
             │                    │
             │ từ chối            │ thanh lý / hỏng nặng
             ▼                    ▼
          REJECTED             RETIRED
```

| Chuyển | Điều kiện | Quy tắc |
|---|---|---|
| `PENDING_APPROVAL` → `ACTIVE` | `BRANCH_MANAGER` duyệt (xe công ty) · KYC thủ công (xe đối tác) · xe đã có giá ngày | BR-010, BR-009, BR-234 |
| `ACTIVE` → `INACTIVE` | Tạm ngừng khai thác. **Không huỷ đơn đã xác nhận** | — |
| `* ` → `RETIRED` | **Chặn nếu còn đơn hiệu lực trong tương lai** | BR-014 |

Ngoài ra xe `ACTIVE` vẫn **không cho thuê được** khi: hết hạn đăng kiểm (BR-012) · đang có khoá lịch
`BLOCKED` (BR-011, BR-007).

---

## 5. Kỳ thanh toán hợp đồng dài hạn

```text
UPCOMING ──► DUE ──► PAID
              │
              ▼
           GRACE (trễ 1–3 ngày, chưa phạt)
              │
              ▼
          OVERDUE (trễ 4–7 ngày, tính phí trễ hạn)
              │
              ▼
         SUSPENDED (trễ >7 ngày, đình chỉ hợp đồng, thu hồi xe)
```
BR-220, BR-221

| Bất biến | Quy tắc |
|---|---|
| Hợp đồng có kỳ `OVERDUE` hoặc `SUSPENDED` chặn: gia hạn, đặt xe mới, nhận ưu đãi | BR-224 |
| `SUSPENDED` trừ thế chấp để bù kỳ chưa trả | BR-221, BR-222 |
| Thông báo bám đúng các mốc này | BR-1005 |

---

## 6. Phân công người lái — `DriverAssignment`

```text
ASSIGNED ──► IN_PROGRESS ──► COMPLETED
    │
    ├──► REASSIGNED   (đổi tài xế — CHỈ trước giờ khởi hành)
    └──► CANCELLED    (đơn huỷ)
```

| Bất biến | Quy tắc |
|---|---|
| Một tài xế không có hai phân công chồng giờ | BR-507 |
| Sau `IN_PROGRESS`, người lái là **dữ liệu bất biến** của chuyến | BR-505 |
| Mọi lần `REASSIGNED` đều ghi lịch sử **và báo khách** | BR-506 |
| Không có phân công `ASSIGNED` thì đơn không vào được `IN_PROGRESS` | BR-502 |

---

## 7. Điều chuyển xe — `VehicleTransfer`

```text
REQUESTED ──► APPROVED ──► IN_TRANSIT ──► RECEIVED
    │                                        
    └──► REJECTED
```

| Chuyển | Ai | Quy tắc |
|---|---|---|
| *(tạo)* → `REQUESTED` | `BRANCH_MANAGER` chi nhánh đang giữ xe | BR-006 |
| `REQUESTED` → `APPROVED` | `OPERATIONS_MANAGER` hoặc `ADMIN` | BR-006 |
| `IN_TRANSIT` → `RECEIVED` | `BRANCH_MANAGER` chi nhánh nhận | BR-006 |

| Bất biến | Quy tắc |
|---|---|
| `IN_TRANSIT` tạo khoá lịch `BLOCKED` — xe không cho thuê được | BR-007 |
| `RECEIVED` mới đổi chi nhánh của xe, và ghi lịch sử | BR-008 |
| Thiếu bước `RECEIVED` thì xe kẹt ở `IN_TRANSIT` vô thời hạn — đây là lý do bước này bắt buộc | BR-006 |

---

## 8. Sự cố — `Incident`

```text
REPORTED ──► ASSESSING ──► FAULT_DETERMINED ──► RESOLVED
                 │                │
                 │                └──► DISPUTED ──► ESCALATED ──► RESOLVED
                 ▼
            (thiệt hại < 2 triệu: chi nhánh quyết ngay, bỏ qua giám định)
```

| Bậc | Ai quyết | Quy tắc |
|---|---|---|
| Thiệt hại < 2 triệu, nguyên nhân rõ | `BRANCH_STAFF` | BR-426 |
| Không rõ | Gara / đơn vị kỹ thuật độc lập | BR-426 |
| Hai bên không đồng ý | Hội đồng `OPERATIONS_MANAGER` + `ACCOUNTANT` | BR-426 |

| Bất biến | Quy tắc |
|---|---|
| Bằng chứng ngang nhau ⇒ nghiêng về khách | BR-426 |
| Khách chịu tối đa 2 triệu mỗi sự cố, phần vượt do bảo hiểm | BR-602 |
| Quyết định bậc 3 phải thông báo lý do cho khách **bằng văn bản** | BR-426 |

---

## 9. Những chỗ hai máy trạng thái phải khớp nhau

Đây là các bất biến xuyên thực thể — nơi dễ sinh dữ liệu sai nhất.

| Bất biến | Vì sao quan trọng |
|---|---|
| `Booking.CONFIRMED` ⟺ `Reservation.CONFIRMED`, đổi trong **cùng một transaction** | Tách ra sẽ sinh đơn không có chỗ, hoặc chỗ giữ không chủ |
| `Booking.IN_PROGRESS` ⟹ `Reservation.IN_USE` **và** `balance.PAID` **và** (nếu có tài xế) `DriverAssignment.IN_PROGRESS` | Chuyến chạy mà thiếu một trong ba là lỗ hổng pháp lý hoặc lỗ hổng tiền |
| `Booking.CANCELLED` ⟹ `Reservation.RELEASED` | Xe bị khoá vĩnh viễn bởi đơn đã chết |
| `Booking.COMPLETED` ⟹ `collateral.RETURNED` | Giữ tiền của khách sau khi xong việc |
| `Booking.CONVERTED` ⟹ cọc `TRANSFERRED` **và** chỗ mới đã giữ được **trước khi** nhả chỗ cũ | Khách mất cả hai xe |
| `Vehicle.RETIRED` ⟹ không còn `Reservation` hiệu lực trong tương lai | BR-014 |
| Kỳ hợp đồng `SUSPENDED` ⟹ khách không tạo được `Booking` mới | BR-224 |

---

## 10. Bốn cạnh bổ sung sau khi rà máy trạng thái

| Tình huống | Xử lý | Quy tắc |
|---|---|---|
| `CONFIRMED` mà xe hỏng **trước** giờ giao | Ưu tiên đổi xe (BR-305); không có xe thay → công ty huỷ, hoàn 100% | BR-127 |
| Khách **gia hạn** chuyến | Tạo **bản ghi gia hạn** gắn vào đơn; không sửa đơn gốc; khoá lịch nối dài | BR-427 |
| Sự cố phát hiện **sau** `COMPLETED` | **Không mở lại đơn** — tạo hồ sơ sự cố độc lập tham chiếu đơn | BR-428 |
| Hợp đồng `SUSPENDED` mà khách không trả xe | Chuyển **thu hồi tài sản**, cần `ADMIN` kích hoạt | BR-233 |
