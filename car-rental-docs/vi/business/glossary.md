# Glossary — Từ điển thuật ngữ

> **Bắt buộc dùng đúng.** Mọi tài liệu, tên bảng, tên trường, tên API, tên biến và giao diện người
> dùng đều dùng đúng những từ ở đây. Không dùng từ đồng nghĩa, không tự đặt từ mới.
>
> Tài liệu này ra đời vì một sự cố có thật: Chief Architect dùng lẫn "cọc" và "thế chấp" trong
> business-rules v4, và Tech Owner phải chỉ ra. Hai từ đó khác nhau về bản chất, về cách ghi sổ, và
> về việc tiền có quay lại túi khách hay không.

---

## 1. Tiền — nhóm dễ nhầm nhất

| Tiếng Việt | Trong code | Nghĩa chính xác | Có hoàn không |
|---|---|---|---|
| **Cọc** | `deposit` | Trả trước một phần tiền thuê: 30% gói ngày/giờ, 40% gói tháng | **Không** — trừ vào tiền thuê |
| **Phần còn lại** | `balance` | Phần tiền thuê trả khi nhận xe | Không — là tiền thuê |
| **Thế chấp** | `collateral` | Tài sản bảo đảm: tiền mặt hoặc xe máy + cà vẹt | **Có** — hoàn 100% ngay sau khi trả xe |
| **Hoa hồng** | `commission` | Phần nền tảng giữ lại trên đơn xe đối tác | — |
| **Chi trả đối tác** | `payout` | Phần cọc còn lại sau khi trừ hoa hồng, trả cho chủ xe | — |

> **Ba khoản trên là ba thứ khác nhau.** Cọc là doanh thu tương lai. Thế chấp là tài sản giữ hộ —
> ghi sổ là **khoản phải trả**, không phải doanh thu. Nhầm chúng dẫn tới sai sót tiền và báo cáo lãi
> ảo. Xem BR-201, BR-202, BR-209 → BR-212.

### Các khoản phí

| Tiếng Việt | Trong code | Nghĩa | Quy tắc |
|---|---|---|---|
| Tiền năng lượng | `energy_charge` | Tiền xăng/điện khách tiêu thụ, tính theo lượng thật | BR-415 |
| Phí dịch vụ nhiên liệu | `refuel_service_fee` | **Chế tài** khi trả xe thiếu nhiên liệu — không phải tiền năng lượng | BR-414 |
| Phụ phí vượt km | `excess_mileage_fee` | Phí khi vượt giới hạn km của cả chuyến | BR-401, BR-404 |
| Phí trả trễ | `late_return_fee` | Phí khi trả xe muộn hơn giờ cam kết | BR-408, BR-118 |
| Phí giao xe | `delivery_fee` | Phí giao xe tận nơi, tính theo km từ chi nhánh | BR-412 |
| Phí tài xế | `driver_fee` | Phí thuê xe có tài xế, tính theo ngày | BR-219 |
| Phụ phí qua đêm | `overnight_allowance` | Phụ phí khi chuyến buộc tài xế ở lại ngoài địa bàn | BR-223 |
| Phí bảo hiểm | `insurance_fee` | Phí bảo hiểm theo chuyến, **hiện thành dòng riêng** | BR-231 |
| Phí huỷ | `cancellation_fee` | Phần cọc bị giữ lại khi khách huỷ | BR-301 |
| Phí điều chuyển | `relocation_fee` | Phí khi khách trả xe sai chi nhánh | BR-413 |
| Phí trễ hạn | `overdue_fee` | Phí khi khách trễ kỳ thanh toán thuê dài hạn | BR-221 |
| Mức miễn thường | `deductible` | Mức tối đa khách chịu mỗi sự cố (2.000.000đ) | BR-602 |

---

## 2. Xe và nguồn cung

| Tiếng Việt | Trong code | Nghĩa |
|---|---|---|
| Xe | `Vehicle` | Một chiếc xe cụ thể, định danh bằng biển số |
| Loại sở hữu | `ownership_type` | `COMPANY` \| `PARTNER` — phân biệt theo **dòng tiền** |
| Loại đối tác | `partner_type` | `INDIVIDUAL` \| `BUSINESS` — phân biệt theo **hợp đồng và kênh** |
| Loại nhiên liệu | `fuel_type` | `PETROL` \| `DIESEL` \| `ELECTRIC` \| `HYBRID` |
| Chi nhánh | `Branch` | Điểm đỗ xe công ty, có địa điểm cụ thể và một quản lý |
| Điều chuyển xe | `VehicleTransfer` | Chuyển xe từ chi nhánh này sang chi nhánh khác |
| Thanh lý xe | `retire` | Ngừng khai thác vĩnh viễn một chiếc xe |

---

## 3. Lịch xe

| Tiếng Việt | Trong code | Nghĩa |
|---|---|---|
| Khoá lịch | `Reservation` | Một khoảng thời gian xe **không cho thuê được**, bất kể lý do |
| Giữ chỗ | `HELD` | Khoá lịch tạm trong lúc khách thanh toán — hết hạn sau 1 tiếng |
| Khoảng đệm | `turnaround_buffer` | Thời gian giữa hai lượt thuê để dọn và nạp nhiên liệu |
| Khoá do giấy tờ | `COMPLIANCE_HOLD` | Khoá lịch vì đăng kiểm hoặc TNDS hết hạn — khoảng không chặn trên |
| Dời điểm bắt đầu khoá giấy tờ | `moveComplianceHoldStart` | Chỉ khi giấy tờ được gia hạn (BR-015). **Không gọi là "hoãn"** (`postpone`): BR-013 dùng "hoãn đăng kiểm" cho việc vi phạm pháp luật |

> **"Giữ chỗ" và "khoá lịch" không đồng nghĩa.** Khoá lịch là khái niệm chung: đơn thuê, bảo dưỡng,
> đăng kiểm, điều chuyển, chủ xe tự khoá — tất cả đều là khoá lịch. Giữ chỗ là **một trạng thái**
> của khoá lịch, có thời hạn. Xem BR-102, BR-103, BR-104.

---

## 4. Đặt xe và chuyến đi

| Tiếng Việt | Trong code | Nghĩa |
|---|---|---|
| Đơn thuê | `Booking` | Một lần thuê xe, từ lúc đặt tới lúc hoàn tất |
| Báo giá | `Quote` | Kết quả tính giá, bất biến, có hạn dùng |
| Gói giờ | `HOURLY` | Thuê tối thiểu 4 giờ |
| Gói ngày | `DAILY` | Thuê theo ngày |
| Gói dài hạn | `MONTHLY` | Thuê theo tháng, có hợp đồng riêng |
| Tự lái | `SELF_DRIVE` | Khách tự cầm lái |
| Có tài xế | `WITH_DRIVER` | Nền tảng hoặc chủ xe bố trí người lái |
| Người lái được khai báo | `declared_driver` | Người lái của chuyến, đã xác minh GPLX, khách nhìn thấy được |
| Gia hạn | `extend` | Kéo dài chuyến đang diễn ra |
| Đổi xe giữ cọc | `rebook` | Chuyển cọc từ đơn cũ sang đơn mới thay vì huỷ |
| Đã chuyển đổi | `CONVERTED` | Trạng thái đơn cũ sau khi đổi xe — **không phải** `CANCELLED` |

> **"Huỷ" và "chuyển đổi" phải tách bạch.** Đơn được đổi sang xe khác mà ghi là huỷ sẽ làm sai tỷ lệ
> huỷ trong báo cáo và mất khả năng đo hiệu quả nghiệp vụ giữ khách. Xem BR-307.

---

## 5. Bàn giao và bằng chứng

| Tiếng Việt | Trong code | Nghĩa |
|---|---|---|
| Biên bản bàn giao | `HandoverRecord` | Ảnh tình trạng xe, ODO, nhiên liệu, mốc thời gian, chữ ký hai bên |
| ODO | `odometer_km` | Số km trên đồng hồ xe |
| Hồ sơ bằng chứng | `evidence_bundle` | Hợp đồng + CCCD + GPLX + biên bản bàn giao |
| Phạt nguội | `TrafficViolation` | Vi phạm giao thông phát hiện sau, thông báo gửi cho chủ xe |
| Bản ghi nạp nhiên liệu | `RefuelRecord` | Lượng nạp, số tiền, thời điểm, trạm — cơ sở tính tiền năng lượng |

> **Biên bản bàn giao không phải giấy tờ hành chính.** Theo Nghị định 336/2025/NĐ-CP, nó là thứ
> quyết định công ty hay khách trả tiền phạt nguội. Xem BR-702.

---

## 6. Con người

| Vai | Nghĩa | Giai đoạn |
|---|---|---|
| `CUSTOMER` | Khách thuê | 1 |
| `BRANCH_STAFF` | Nhân viên vận hành chi nhánh | 1 |
| `BRANCH_MANAGER` | Quản lý chi nhánh | 1 |
| `DELIVERY_STAFF` | Nhân viên giao xe | 1 |
| `DRIVER` | Tài xế là nhân viên công ty | 1 |
| `ACCOUNTANT` | Kế toán | 1 |
| `OPERATIONS_MANAGER` | Quản lý vận hành — duyệt điều chuyển xe | 1 |
| `ADMIN` | Toàn quyền | 1 |
| `HOST` | Đối tác cá nhân | 2 |
| `BUSINESS_PARTNER` | Đối tác doanh nghiệp | 3 |
| `BUSINESS_PARTNER_STAFF` | Tài khoản con của đối tác doanh nghiệp | 3 |
| `FREELANCE_DRIVER` | Tài xế tự do | 3 |

> **"Chủ xe" là vai nghiệp vụ, không phải vai hệ thống.** Với xe đối tác, chủ xe là `HOST` hoặc
> `BUSINESS_PARTNER`. Với xe công ty, người đóng vai "chủ xe" trong các thao tác bàn giao và xác nhận
> tiền là `BRANCH_STAFF` hoặc `DELIVERY_STAFF`. Xem BR-204.

---

## 7. Những cặp từ dễ nhầm — tra nhanh

| Đừng nhầm | Với | Khác nhau ở |
|---|---|---|
| **Cọc** (`deposit`) | **Thế chấp** (`collateral`) | Cọc không hoàn, trừ vào tiền thuê. Thế chấp hoàn 100% |
| **Tiền năng lượng** | **Phí dịch vụ nhiên liệu** | Một là hoàn chi phí thật, một là chế tài |
| **Giữ chỗ** (`HELD`) | **Khoá lịch** (`Reservation`) | Giữ chỗ là một trạng thái của khoá lịch, có hạn 1 tiếng |
| **Huỷ** (`CANCELLED`) | **Đã chuyển đổi** (`CONVERTED`) | Chuyển đổi là đổi sang xe khác, không mất khách |
| **Loại sở hữu** | **Loại đối tác** | Một quyết định dòng tiền, một quyết định hợp đồng và kênh |
| **Bảo hiểm TNDS** | **Bảo hiểm thân vỏ** | TNDS bắt buộc theo luật; thân vỏ nền tảng mua theo chuyến |
| **Phí huỷ** | **Bồi thường huỷ** | Phí huỷ là khách chịu; bồi thường là đối tác trả cho khách |
| **Giới hạn km** | **Phụ phí vượt km** | Một là hạn mức, một là tiền phải trả khi vượt |
