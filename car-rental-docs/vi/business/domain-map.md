# Domain Map

> Bản đồ nghiệp vụ: hệ thống chia thành những vùng nào, mỗi vùng chịu trách nhiệm gì, và chúng
> chạm nhau ở đâu. Dựa trên 134 quy tắc trong [`business-rules.md`](business-rules.md).
>
> Đây là **mô hình nghiệp vụ**, chưa phải thiết kế kỹ thuật. Chưa nói tới module code, schema hay API.

---

## 1. Toàn cảnh

```text
┌─────────────────── NGUỒN CUNG ───────────────────┐
│                                                   │
│   branch ──────► vehicle ◄────── fleet-ops        │
│   chi nhánh      danh mục xe     bảo dưỡng        │
│   địa điểm       ownership_type  đăng kiểm        │
│   quản lý        fuel_type       điều chuyển      │
│                                  nạp nhiên liệu   │
│                       │                │          │
│                       ▼                ▼          │
│              ┌────────────────────────────┐       │
│              │       availability         │       │
│              │  NGUỒN SỰ THẬT DUY NHẤT    │       │
│              │  về việc xe rảnh hay bận   │       │
│              │  (đơn thuê · bảo dưỡng ·   │       │
│              │   đăng kiểm · điều chuyển) │       │
│              └────────────┬───────────────┘       │
└───────────────────────────┼───────────────────────┘
                            │
┌─────────────────── NHU CẦU ┼──────────────────────┐
│    search ────────────────►│                      │
│    (KHÔNG biết xe của ai)  │                      │
│                            ▼                      │
│    pricing ──────────► booking ──────► handover   │
│    bảng giá            đơn thuê       biên bản    │
│    loại ngày           gia hạn        ODO         │
│    chiết khấu          đổi xe         nhiên liệu  │
│    phí tài xế          huỷ            chữ ký      │
│    mã giảm giá            │                       │
│                           ▼                       │
│                       dispatch                    │
│                    phân công tài xế               │
│                    và nhân viên giao xe           │
└───────────────────────────┬───────────────────────┘
                            │
┌─────────────────── TIỀN ───┼───────────────────────┐
│   payment ──────────► settlement                   │
│   cọc                 hoa hồng                     │
│   phần còn lại        chi trả đối tác  (GĐ2)       │
│   thế chấp                                         │
│   hoàn tiền           contract                     │
│                       hợp đồng dài hạn             │
│                       kỳ thanh toán                │
└────────────────────────────────────────────────────┘

┌────────── TUÂN THỦ & SAU CHUYẾN ──────────┐
│  compliance      incident      review      │
│  phạt nguội      sự cố         đánh giá    │
│  hồ sơ bằng      xác định lỗi  phản hồi    │
│  chứng           bảo hiểm                  │
└────────────────────────────────────────────┘

Nền tảng dùng chung: identity · notification · audit
```

---

## 2. Từng vùng nghiệp vụ

### 2.1. `identity` — Danh tính
Tài khoản, vai, và giấy tờ của khách.

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Tài khoản = một số điện thoại duy nhất | BR-120 |
| CCCD và GPLX: bổ sung sau, nhưng bắt buộc trước khi đặt xe | BR-106, BR-120 |
| Đổi số điện thoại, xoá tài khoản | BR-123, BR-124 |
| Vai người dùng | BR-801 |
| Hồ sơ vi phạm và mức hạn chế | BR-707, BR-708 |

**Không chịu trách nhiệm:** lưu trữ bằng chứng của đơn đã hoàn tất — đó là `compliance` (BR-123).

### 2.2. `branch` — Chi nhánh
Khái niệm trung tâm của giai đoạn 1.

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Địa điểm, giờ làm việc 06:00–23:00 | BR-119 |
| Một quản lý mỗi chi nhánh | BR-807 |
| Ranh giới dữ liệu giữa các chi nhánh | BR-802, BR-803 |
| Yêu cầu hỗ trợ chéo chi nhánh | BR-804 |
| Đóng chi nhánh | BR-806 |

### 2.3. `vehicle` — Danh mục xe

| Chịu trách nhiệm | Quy tắc |
|---|---|
| `ownership_type` — bất biến sau khi tạo | BR-001 |
| `partner_type`, `fuel_type` | BR-002, BR-410 |
| Mỗi xe công ty thuộc đúng một chi nhánh | BR-003 |
| Giấy tờ hợp lệ theo luật (gồm TNDS) | BR-005 |
| Duyệt xe lên sàn | BR-010, BR-009 |
| Chặn thanh lý khi còn đơn hiệu lực | BR-014 |

**Không chịu trách nhiệm:** lịch bận của xe. Đó là `availability`.

### 2.4. `fleet-ops` — Vận hành đội xe

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Bảo dưỡng định kỳ (km hoặc thời gian, mốc nào trước) | BR-011 |
| Đăng kiểm | BR-012 |
| Theo dõi hạn giấy tờ (đăng kiểm, TNDS) và tự khoá lịch khi hết hạn | BR-015, BR-016, BR-017 |
| Bảo dưỡng khi xe đang thuê dài hạn | BR-013 |
| Điều chuyển xe giữa chi nhánh | BR-006, BR-007, BR-008 |
| Nạp nhiên liệu và ghi bản ghi | BR-420, BR-421 |

Hồ sơ bảo dưỡng là **bằng chứng bảo vệ công ty** khi tranh chấp lỗi (BR-426) — không chỉ là việc
giữ xe bền.

### 2.5. `availability` — Lịch xe
**Nguồn sự thật duy nhất** trả lời: chiếc xe này có rảnh trong khoảng thời gian này không?

Mọi lý do khiến xe bận đều nằm ở đây, cùng một chỗ: đơn thuê · giữ chỗ chờ thanh toán · bảo dưỡng ·
đăng kiểm · điều chuyển chi nhánh · chủ xe tự khoá.

| Bất biến | Quy tắc |
|---|---|
| Không hai khoá lịch nào chồng thời gian trên cùng một xe | BR-104 |
| Giữ chỗ hết hạn sau 1 tiếng thì tự nhả | BR-103 |
| Khoảng đệm nằm **bên trong** khoảng khoá, không phải chuyện hiển thị | BR-109, BR-116 |
| *(BR-119 giờ làm việc thuộc `booking`, không thuộc `availability` — xem §2.8)* | — |

**Vì sao gộp mọi lý do vào một chỗ:** nếu lịch bảo dưỡng nằm riêng, sẽ không có gì ngăn hệ thống cho
khách đặt thuê đè lên ngày xe nằm gara.

### 2.6. `search` — Tìm xe

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Tìm theo vị trí, lọc nhiều tiêu chí | — |
| Chỉ hiện xe đã duyệt và còn trống | BR-010, BR-104 |
| Nhãn "Miễn thế chấp" | BR-110 |

**Ràng buộc quan trọng:** `search` **không được biết** xe thuộc công ty hay đối tác — loại sở hữu
không phải tiêu chí xếp hạng (BR-112). Đây là một ranh giới cần giữ sạch.

### 2.7. `pricing` — Giá

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Giá theo loại ngày: thường, cuối tuần, lễ tết | BR-218 |
| Giá gói giờ (50% giá ngày) | BR-114 |
| Chiết khấu theo độ dài thuê | BR-230 |
| Phí tài xế, phụ phí qua đêm | BR-219, BR-223 |
| Phí giao xe theo khoảng cách | BR-412 |
| Phí bảo hiểm — dòng riêng | BR-231 |
| Mã giảm giá | BR-227, BR-228 |
| Báo giá bất biến, có hạn dùng | BR-122 |

### 2.8. `booking` — Đơn thuê
Vòng đời đơn, và là nơi điều phối các vùng khác.

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Giới hạn thời điểm đặt: sớm nhất 1–3 giờ, xa nhất 6 tháng | BR-121 |
| Giờ nhận/trả phải trong khung làm việc của chi nhánh | BR-119 |
| Ba gói: giờ (tối thiểu 4h), ngày, tháng | BR-113 |
| Trả xe đúng chi nhánh đã nhận | BR-105, BR-413 |
| Gia hạn nếu xe còn trống | BR-423 |
| Trả sớm không hoàn | BR-424 |
| Huỷ đơn ba mốc | BR-301, BR-302, BR-303 |
| Đổi xe giữ cọc | BR-305 → BR-309 |
| Đóng băng giá và hoa hồng tại thời điểm đặt | BR-122, BR-208 |

**Không chịu trách nhiệm:** quyết định xe còn trống (`availability`), tính tiền (`pricing`),
thu tiền (`payment`).

### 2.9. `dispatch` — Điều phối người

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Người lái được khai báo cho mỗi chuyến có tài xế | BR-501 → BR-504 |
| Đổi tài xế chỉ trước giờ khởi hành, phải báo khách | BR-505, BR-506 |
| Một tài xế không hai chuyến chồng giờ | BR-507 |
| Tài xế nghỉ đột xuất | BR-508 |
| Phân công nhân viên giao xe | BR-412 |

Tài xế và nhân viên giao xe là **tài nguyên tranh chấp** giống chiếc xe — cùng bản chất với BR-104.

### 2.10. `handover` — Bàn giao

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Biên bản bàn giao bất biến: ảnh, ODO, nhiên liệu, chữ ký | BR-407 |
| Đối chiếu người nhận xe với giấy tờ trong đơn | BR-107 |
| Chủ xe/nhân viên xác nhận đã nhận đủ tiền → chuyến bắt đầu | BR-204, BR-217 |
| Khách xác nhận cùng lúc | BR-205 |
| Quyết toán năng lượng tại chỗ | BR-418, BR-419 |
| Tra cứu phạt nguội khi nhận lại xe | BR-706 |

### 2.11. `payment` — Tiền vào ra

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Cọc, phần còn lại, thế chấp | BR-201 → BR-212 |
| Xác nhận thủ công, chống trùng | BR-213 |
| Hoàn tiền trong 3 ngày làm việc | BR-215, BR-232 |
| Không tin số tiền client gửi | BR-216 |
| Hoá đơn VAT | BR-226 |

### 2.12. `settlement` — Chia tiền (GĐ2)
**Nơi duy nhất dòng tiền phân nhánh theo loại sở hữu.**

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Hoa hồng trừ từ cọc, không vượt tỷ lệ cọc | BR-206, BR-207 |
| Nền tảng chịu chi phí khuyến mãi | BR-229 |
| Ứng trả chi phí năng lượng không truy thu được | BR-422 |

### 2.13. `contract` — Hợp đồng dài hạn

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Kỳ thanh toán hàng tháng | BR-220 |
| Thang xử lý trễ kỳ | BR-221 |
| Thế chấp đủ bù một kỳ | BR-222 |
| Trạng thái quá hạn chặn nghiệp vụ khác | BR-224 |

### 2.14. `incident` — Sự cố

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Xe hỏng giữa đường, phân theo lỗi | BR-425 |
| Quy trình ba bậc xác định lỗi | BR-426 |
| Bảo hiểm theo chuyến, mức miễn thường 2 triệu | BR-601 → BR-603 |

### 2.15. `compliance` — Tuân thủ và bằng chứng

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Trả lời "ai lái xe X lúc T" nhiều tháng sau | BR-701 |
| Hồ sơ bằng chứng bất biến | BR-702, BR-123 |
| Trách nhiệm vi phạm: tự lái vs có tài xế | BR-703 |
| GPS và camera hành trình | BR-705 |

### 2.16. `review` — Đánh giá

| Chịu trách nhiệm | Quy tắc |
|---|---|
| Đánh giá công khai, 5 sao + bình luận | BR-901, BR-903 |
| Chỉ khách đã hoàn tất đơn mới đánh giá được | BR-902 |

---

## 3. Các bất biến quan trọng nhất

Xếp theo mức thiệt hại nếu vi phạm:

| # | Bất biến | Vùng | Quy tắc |
|---|---|---|---|
| 1 | Không hai khoá lịch chồng thời gian trên một xe | `availability` | BR-104 |
| 2 | Chuyến không khởi hành khi chưa có người lái khai báo | `dispatch` | BR-502 |
| 3 | Một tài xế không hai chuyến chồng giờ | `dispatch` | BR-507 |
| 4 | Mọi thao tác tiền là idempotent | `payment` | BR-213, BR-309 |
| 5 | Hoa hồng không vượt tỷ lệ cọc | `settlement` | BR-207 |
| 6 | `ownership_type` bất biến sau khi tạo | `vehicle` | BR-001 |
| 7 | Biên bản bàn giao và hồ sơ bằng chứng không sửa, không xoá | `compliance` | BR-407, BR-702 |
| 8 | Đơn đóng băng giá và hoa hồng tại thời điểm đặt | `booking` | BR-122, BR-208 |
| 9 | Xe hết hạn đăng kiểm không được cho thuê | `fleet-ops` | BR-012 |
| 10 | Chỉ khách đã hoàn tất đơn mới đánh giá được | `review` | BR-902 |

---

## 4. Bốn ranh giới phải giữ sạch

Đây là những chỗ dễ mục nát nhất — mỗi cái đều là một quyết định có lý do, và sẽ bị vi phạm dần
khi ai đó vội.

**1. `search` không biết loại sở hữu.** Loại sở hữu không phải tiêu chí xếp hạng (BR-112). Nếu
`search` bắt đầu biết, nó sẽ dần thành nơi cài thiên vị, và đối tác sẽ nhận ra.

**2. Chỉ `availability` ghi lịch xe.** `fleet-ops` khoá xe bảo dưỡng bằng cách **nhờ** `availability`,
không tự ghi. Nếu mỗi vùng tự ghi lịch, bất biến số 1 mất hiệu lực.

**3. `settlement` là nơi duy nhất dòng tiền phân nhánh theo loại sở hữu.** `payment` chỉ biết
"tiền đã vào chưa". Nếu `payment` bắt đầu biết về hoa hồng, mọi thay đổi chính sách hoa hồng sẽ
đụng vào vùng nguy hiểm nhất hệ thống.

**4. `booking` điều phối, không tự làm.** Không tự quyết xe còn trống, không tự tính tiền, không tự
thu tiền. Đây là vùng dễ phình thành nơi chứa mọi thứ nhất.

---

## 5. Vùng nào thuộc giai đoạn nào

| Giai đoạn 1 | Giai đoạn 2 | Giai đoạn 3 |
|---|---|---|
| identity · branch · vehicle · fleet-ops · availability · search · pricing · booking · dispatch · handover · payment · contract · incident · compliance · review · notification · audit | **settlement** (hoa hồng, chi trả) · vùng đối tác cá nhân trong `vehicle` và `dispatch` | Đối tác doanh nghiệp · tài xế tự do · tài khoản con · hoá đơn VAT tự động |

Giai đoạn 1 **không có** `settlement` vì xe công ty không phải chia tiền cho ai. Nhưng mô hình dữ
liệu phải chứa sẵn `ownership_type` từ đầu (BR-001) — thêm nó vào sau khi đã có dữ liệu thật là
một trong những việc đắt nhất có thể tự gây ra.
