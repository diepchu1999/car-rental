# Business Rules — v1 (Giai đoạn 1)

- **Nguồn:** phỏng vấn Tech Owner 22–24/08/2026, 7 vòng. Chi tiết và lý lẽ ở
  [`requirements-analysis.md`](requirements-analysis.md).
- **Người soạn:** Chief Architect. **Người chốt:** Tech Owner.
- **Phạm vi:** giai đoạn 1 (xe công ty). Quy tắc của GĐ2–3 chỉ ghi khi đã chốt, đánh dấu rõ giai đoạn.

## Cách đọc

Mỗi quy tắc có mã cố định. **Mã không bao giờ được tái sử dụng** — quy tắc bị bỏ thì đánh dấu
`[ĐÃ HUỶ]`, không xoá và không gán mã đó cho quy tắc khác. Code và test trích mã này.

| Ký hiệu | Nghĩa |
|---|---|
| ✅ | Tech Owner đã chốt |
| 🟡 | Chief Architect đề xuất, **chưa** được duyệt — không hiện thực |
| GĐ | Giai đoạn áp dụng |

**Nhóm mã:** `0xx` xe & nguồn cung · `1xx` đặt xe · `2xx` tiền · `3xx` huỷ & đổi xe ·
`4xx` bàn giao & trả xe · `5xx` tài xế · `6xx` bảo hiểm · `7xx` phạt nguội · `8xx` phân quyền.

---

## 0xx — Xe và nguồn cung

### BR-001 — Hai loại sở hữu ✅ GĐ1
Mỗi xe có `ownership_type` ∈ `{ COMPANY, PARTNER }`, xác định **dòng tiền** của mọi đơn thuê xe đó.
Giá trị này **bất biến sau khi tạo** — một chiếc xe không chuyển từ PARTNER sang COMPANY.
*Nguồn: phân tích §2, §2.1.*

### BR-002 — Hai loại đối tác ✅ GĐ2
Xe `PARTNER` có thêm `partner_type` ∈ `{ INDIVIDUAL, BUSINESS }`, xác định **hợp đồng, kênh làm việc
và cách bố trí tài xế**. Không ảnh hưởng dòng tiền — dòng tiền của hai loại đối tác giống hệt nhau.
*Nguồn: §2.1.*

### BR-003 — Xe công ty thuộc chi nhánh ✅ GĐ1
Mỗi xe `COMPANY` thuộc **đúng một chi nhánh**. Một chi nhánh có nhiều xe.
Vị trí của xe khi tìm kiếm là vị trí chi nhánh.
*Nguồn: §3.*

### BR-004 — Xe đối tác không thuộc chi nhánh ✅ GĐ2
Xe `PARTNER` không gắn với chi nhánh nào. Vị trí là địa điểm chủ xe hẹn.
*Nguồn: §3.*

### BR-005 — Giấy tờ hợp lệ theo luật ✅ GĐ1
Mọi xe lên sàn phải có đầy đủ giấy tờ theo luật hiện hành, **bao gồm bảo hiểm TNDS bắt buộc**.

Đây là **cổng vào** — kiểm một lần khi duyệt xe lên sàn. Tại thời điểm duyệt, đăng kiểm và TNDS
phải **có mặt và chưa hết hạn**; xe đã hết hạn giấy tờ thì không duyệt được.

Việc giấy tờ còn hạn **trong suốt thời gian khai thác** là chuyện khác, xem BR-015.
*Nguồn: §12.2. Làm rõ điều kiện tại cổng duyệt: Chief Architect, 07/09/2026.*

### BR-006 — Điều chuyển xe giữa chi nhánh ✅ GĐ1
Quy trình bắt buộc ba bước:
1. `BRANCH_MANAGER` chi nhánh đang giữ xe **yêu cầu** điều chuyển.
2. `OPERATIONS_MANAGER` **duyệt**. `ADMIN` duyệt được mọi yêu cầu.
3. `BRANCH_MANAGER` chi nhánh nhận **xác nhận đã nhận xe**.

Thiếu bước 3, xe ở trạng thái "đang di chuyển" vô thời hạn.
*Nguồn: §3.1.*

### BR-007 — Xe đang điều chuyển không cho thuê ✅ GĐ1
Trong khoảng thời gian điều chuyển, xe bị khoá lịch và **không xuất hiện trong kết quả tìm kiếm**
cho khoảng đó. Cùng cơ chế với khoá lịch bảo dưỡng.
*Nguồn: §3.1.*

### BR-008 — Lịch sử chi nhánh của xe ✅ GĐ1
Mỗi lần điều chuyển ghi lịch sử. Hệ thống phải trả lời được "tại thời điểm T, xe X thuộc chi nhánh nào".
*Nguồn: §3.1.*

### BR-009 — Duyệt xe đối tác lên sàn bằng KYC thủ công ✅ GĐ2
Xe của đối tác chỉ lên sàn sau khi hồ sơ được **duyệt KYC thủ công** — nhân viên xem xét giấy tờ và
phê duyệt bằng tay. Chưa tự động hoá ở giai đoạn này.
⚠️ Danh mục giấy tờ bắt buộc và vai nào duyệt: chưa chốt.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-010 — Xe công ty cần duyệt trước khi lên sàn ✅ GĐ1
Xe công ty chỉ hiển thị cho khách sau khi **`BRANCH_MANAGER` duyệt**. Quản lý chi nhánh là người chịu
trách nhiệm về chiếc xe, nên là người xác nhận xe đủ điều kiện cho thuê.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-011 — Bảo dưỡng định kỳ ✅ GĐ1
Lịch bảo dưỡng tính theo **km hoặc thời gian, mốc nào đến trước**.
Giá trị khởi điểm: **5.000 km hoặc 6 tháng** — là tham số cấu hình được (BR-225).

Xe cho thuê chạy nhiều nên thường chạm mốc km trước, nhưng xe nằm bãi lâu vẫn xuống dầu và ắc quy
nên phải có cả mốc thời gian.

Lịch bảo dưỡng **khoá lịch xe** như mọi lý do bận khác (BR-104). Hồ sơ bảo dưỡng lưu bất biến —
nó là bằng chứng bảo vệ công ty khi tranh chấp lỗi (BR-426).
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-012 — Đăng kiểm ✅ GĐ1
Hệ thống theo dõi hạn đăng kiểm của từng xe và **khoá lịch xe** trong thời gian đi đăng kiểm.

Xe hết hạn đăng kiểm **không được cho thuê** — ràng buộc pháp luật, không phải lựa chọn vận hành.
Cơ chế chặn và cách xác định "hết hạn" theo BR-015 và BR-016.
*Nguồn: yêu cầu pháp lý, hệ quả của BR-005.*

### BR-015 — Giấy tờ có hạn của xe, và cách chặn khi hết hạn ✅ GĐ1
Xe có những giấy tờ **hết hạn theo thời gian**, không phải kiểm một lần rồi thôi:

| Giấy tờ | Bắt buộc | Ghi chú |
|---|---|---|
| **Đăng kiểm** | Có | Theo luật |
| **Bảo hiểm TNDS** | Có | Theo luật, hết hạn hàng năm |
| Bảo hiểm thân vỏ | Không | Nền tảng mua theo chuyến (BR-601) |

**Cơ chế chặn:** giấy tờ hết hạn ngày `D` thì hệ thống **tự động tạo một khoá lịch từ `D` trở đi,
không có chặn trên**. Xe biến mất khỏi kết quả tìm kiếm cho mọi khoảng sau `D`, và không đặt được.

Gia hạn giấy tờ thì **dời điểm bắt đầu của khoá lịch** tới hạn mới. Không xoá rồi tạo lại.

**Vì sao dùng khoá lịch thay vì kiểm tra lúc tạo đơn:** nếu chỉ kiểm lúc đặt, `search` phải lặp lại
đúng logic đó — không lặp thì khách thấy xe, chọn xe, rồi bị từ chối ở bước cuối. Dùng khoá lịch thì
`search` và `booking` tự động tôn trọng mà không cần thêm dòng nào, vì cả hai vốn đã đọc lịch xe.
*Nguồn: Tech Owner chốt 29/08/2026.*

### BR-016 — Kiểm hợp lệ theo khoảng thuê, không theo hôm nay ✅ GĐ1
Xe phải hợp lệ **suốt khoảng khách thuê**, không phải chỉ hợp lệ tại thời điểm đặt.

BR-121 cho đặt trước tới 6 tháng. Nên xe có đăng kiểm hết hạn 15/09 vẫn hợp lệ hôm nay 29/08,
nhưng **không được cho đặt chuyến ngày 20/09**.

Hệ quả: **chặn ngay lúc tạo đơn**, không cho tạo rồi xử lý sau. Cơ chế khoá lịch ở BR-015 làm việc
này tự động — khoảng thuê chồng lên khoá lịch thì đơn bị từ chối như mọi trường hợp trùng lịch khác.

Đánh đổi đã được chấp nhận: chi nhánh gia hạn muộn thì mất doanh thu những ngày đó. Đổi lại khách
**không bao giờ** bị huỷ đơn vì lý do giấy tờ — mà huỷ đơn từ phía công ty là BR-303: hoàn 100%,
cần quản lý duyệt, và mất uy tín.
*Nguồn: Tech Owner chốt 29/08/2026.*

### BR-017 — Cảnh báo trước khi giấy tờ hết hạn ✅ GĐ1
Hệ thống cảnh báo `BRANCH_MANAGER` (xe công ty) hoặc chủ xe (xe đối tác) trước khi mỗi giấy tờ
hết hạn. Thời gian báo trước là tham số cấu hình được (BR-225), giá trị khởi điểm **30 ngày**.

30 ngày là để còn kịp đặt lịch đăng kiểm và mua bảo hiểm — báo trước một tuần thì thường đã muộn.
*Nguồn: Tech Owner chốt 29/08/2026.*

### BR-013 — Bảo dưỡng và đăng kiểm khi xe đang thuê dài hạn ✅ GĐ1
Hợp đồng thuê 6 tháng chắc chắn chạm mốc bảo dưỡng và có thể chạm hạn đăng kiểm.

**Khách tự đưa xe đi, công ty trả tiền.** Công ty chỉ định gara, hẹn lịch, khách đưa xe tới.
Điều khoản này phải nằm trong hợp đồng thuê dài hạn ngay từ khi ký.

Không hoãn được: hoãn đăng kiểm là vi phạm pháp luật (BR-012), hoãn bảo dưỡng 6 tháng là cách nhanh
nhất để hỏng xe — và làm mất luôn hồ sơ bảo dưỡng vốn là bằng chứng bảo vệ công ty (BR-426).
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-014 — Chặn thanh lý xe khi còn đơn hiệu lực ✅ GĐ1
Không được đánh dấu xe thanh lý hoặc ngừng khai thác khi xe còn **đơn thuê hiệu lực trong tương lai**.

Phải xử lý đơn trước: liên hệ khách để **đổi sang xe khác** (BR-305), hoặc huỷ và hoàn **100%**
theo BR-303. Huỷ tự động không báo khách là không chấp nhận được — khách đặt trước cả tháng rồi
bị huỷ mà không ai gọi điện là loại trải nghiệm khách kể lại cho người khác nghe.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-018 — Thuộc tính xe dùng để tìm kiếm ✅ GĐ1
Mỗi xe phải có đủ bốn thuộc tính sau **ngay khi tạo** — thiếu thì bộ lọc của BR-126 không có gì để lọc:

| Thuộc tính | Giá trị |
|---|---|
| Số chỗ | `4` · `5` · `7` · `16` |
| Hộp số | Số sàn · Số tự động |
| Hãng xe | Nhập tự do |
| Dòng xe | Nhập tự do |

Hãng và dòng xe nhập tự do ở giai đoạn 1 — chưa có đủ xe để một danh mục chuẩn đáng công duy trì.
Chuẩn hoá thành danh mục khi số xe tăng.
*Nguồn: Tech Owner duyệt 04/10/2026.*

---

## 1xx — Tìm xe và đặt xe

### BR-101 — Một luồng cho mọi loại xe ✅ GĐ1
Khách tìm và đặt xe **không phân biệt** xe công ty hay xe đối tác. Loại sở hữu không được lộ ra như
một bộ lọc bắt buộc hay một luồng đặt riêng.
*Nguồn: §5.*

### BR-102 — Giữ chỗ ngay khi đặt ✅ GĐ1
Khi khách xác nhận đặt xe, hệ thống **giữ chỗ ngay lập tức** cho toàn bộ khoảng thời gian thuê,
trước khi khách thanh toán.
*Nguồn: §10.4.*

### BR-103 — Thời hạn giữ chỗ 1 tiếng ✅ GĐ1
Chỗ giữ có hiệu lực **1 tiếng** kể từ lúc đặt. Quá hạn mà chưa thanh toán cọc thì hệ thống **tự động
nhả chỗ**, xe trở lại trạng thái cho thuê được.

**Bên gọi không được chọn thời hạn.** Một tiếng là tham số cấu hình (BR-225) do hệ thống áp dụng,
không phải đối số của API giữ chỗ — nếu bên gọi chọn được thì BR-103 không còn là quy tắc.

Giữa lúc hết hạn và lần dọn kế tiếp, bản ghi **vẫn chặn**. Mọi truy vấn "xe còn trống" phải nhất
quán với điều đó, không được báo trống trong khi lệnh ghi vẫn bị từ chối.
*Nguồn: §10.4. Làm rõ 22/09/2026.*

### BR-104 — Không hai đơn chồng thời gian trên một xe ✅ GĐ1
Tại mọi thời điểm, một chiếc xe chỉ thuộc về **tối đa một** đơn thuê đang hiệu lực.
Áp dụng cho cả chỗ đang giữ, đơn đã xác nhận, và đơn đang diễn ra.

Ràng buộc này bao gồm **mọi lý do khiến xe bận**: đơn thuê, bảo dưỡng, đăng kiểm, điều chuyển
chi nhánh, và chủ xe tự khoá lịch.
*Nguồn: §3.1, §10.4.*

### BR-105 — Trả xe đúng chi nhánh đã nhận ✅ GĐ1
Khách **bắt buộc** trả xe tại đúng chi nhánh đã nhận. Không hỗ trợ nhận chi nhánh A trả chi nhánh B.
*Nguồn: §3.*

### BR-106 — Bắt buộc xác minh giấy phép lái xe ✅ GĐ1
Khách thuê **tự lái** phải có giấy phép lái xe **đã được xác minh** trước khi nhận xe.
*Nguồn: §9.*

### BR-106b — Giấy phép lái xe phải còn hạn tới hết chuyến ✅ GĐ1
Khách thuê tự lái chỉ đặt được đơn khi GPLX **còn hạn tới thời điểm kết thúc chuyến**, không phải
chỉ còn hạn tại thời điểm đặt.

Lái xe với bằng hết hạn là vi phạm pháp luật, và bảo hiểm có thể từ chối bồi thường khi có sự cố —
lúc đó mức miễn thường 2 triệu ở BR-602 không còn ý nghĩa gì.

Kiểm hai lần: lúc tạo đơn và lúc bàn giao (BR-107).
*Nguồn: Tech Owner chốt 29/08/2026.*

### BR-107 — Người thuê phải là người lái ✅ GĐ1
Với thuê tự lái, hợp đồng ghi rõ người thuê phải là người lái. Nhân viên **đối chiếu người nhận xe
với giấy tờ trong đơn tại thời điểm bàn giao** — không chỉ ghi trong hợp đồng.
*Nguồn: §9.*

### BR-108 — Không giới hạn độ tuổi ✅ GĐ1
Hệ thống **không** đặt giới hạn tuổi cho khách thuê. Giới hạn thực tế đến từ chính điều kiện cấp
giấy phép lái xe.
*Nguồn: §9.*

### BR-109 — Khoảng đệm giữa hai lượt thuê ✅ GĐ1
Giữa hai lượt thuê liên tiếp trên cùng một xe phải có khoảng đệm **2 giờ** để dọn xe, nạp nhiên liệu,
kiểm tra tình trạng và xử lý khi khách trước trả trễ ít.

Khoảng đệm này được **cộng vào khoảng thời gian khoá lịch xe**, tức là nó nằm bên trong ràng buộc
chống trùng lịch (BR-104), **không phải** chuyện hiển thị cho khách.

⚠️ Chi nhánh **không có trạm sạc/cây xăng tại chỗ** (BR-420) — nhân viên phải lái xe ra trạm bên
ngoài. Nên khoảng đệm phải đủ cho **cả di chuyển đi, nạp, và di chuyển về**, không chỉ thời gian nạp.
Với xe điện dùng sạc nhanh, 2 giờ chỉ đủ khi trạm sạc gần chi nhánh.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-110 — Nhãn "Miễn thế chấp" trên kết quả tìm kiếm ✅ GĐ1
Xe không yêu cầu thế chấp (theo BR-209) được gắn nhãn rõ ràng trên kết quả tìm kiếm và trang chi tiết xe.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-111 — Khách không đến nhận xe ✅ GĐ1
Quá giờ hẹn **2 giờ**, nhân viên **gọi điện cho khách trước**. Không liên lạc được hoặc khách không
đến thì đánh dấu không đến nhận, **nhả xe về trạng thái cho thuê được**.

Khách mất toàn bộ cọc theo BR-301 — thời điểm này luôn nằm trong mốc 24 giờ.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-112 — Không ưu tiên xe công ty trong kết quả tìm kiếm ✅ GĐ1
Kết quả tìm kiếm xếp theo khoảng cách, giá, đánh giá và các tiêu chí khách chọn.
**Loại sở hữu không phải là tiêu chí xếp hạng.**

Công bằng với đối tác là điều kiện để họ chịu lên sàn. Về mặt kỹ thuật, quy tắc này còn có nghĩa
module tìm kiếm **không cần biết** xe thuộc ai — một ranh giới sạch cần giữ.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-113 — Thuê theo giờ, tối thiểu 4 giờ ✅ GĐ1
Hệ thống hỗ trợ **gói thuê theo giờ**, thời gian thuê tối thiểu **4 giờ**, song song với gói theo ngày
và gói dài hạn theo tháng.

Vì phần lớn quy tắc còn lại được thiết kế quanh đơn vị **ngày**, gói giờ có năm điều chỉnh riêng —
BR-114 đến BR-118.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-114 — Giá gói giờ ✅ GĐ1
Gói 4 giờ tính **50% giá ngày**. Phần vượt tính **đúng như trả trễ gói giờ** (BR-118):

- Mỗi giờ vượt = **20% giá gói giờ**, tức **10% giá ngày**.
- Vượt **quá 4 giờ** thì thành **thêm một gói giờ**.
- **Trần:** gói giờ không bao giờ đắt hơn **gói ngày tính cho cùng khoảng** (BR-235). Dưới 24 giờ, trần
  chính là một ngày giá.

Ví dụ: 5 giờ = 50% + 10% = **60%** giá ngày · 8 giờ = 50% + 4 × 10% = **90%** (vượt đúng 4 giờ chưa
phải "quá 4 giờ") · **trên 8 giờ** thành 2 gói trở lên, chạm trần, bằng một ngày giá.

Giá ngày là của chính chiếc xe (BR-234), theo loại ngày của **giờ nhận xe** (BR-218).

Không chia đều giá ngày cho 24: chi phí cố định mỗi lượt (dọn xe, giao nhận, đi nạp nhiên liệu)
không đổi dù khách thuê 4 giờ hay 24 giờ. Tính phần vượt giống trả trễ để đặt trước và trả trễ cùng
một cách tính — như BR-235 làm với gói ngày.
*Nguồn: Tech Owner duyệt 24/08/2026. Làm rõ 09/10/2026 — trước đó phần vượt ghi "theo mức riêng trong
bảng giá", không có mức nào.*

### BR-115 — Giới hạn km của gói giờ ✅ GĐ1
**12,5 km mỗi giờ thuê** — đúng bằng 300 km khi thuê tròn 24 giờ, nên nhất quán với cách tính gộp
của BR-401. Gói 4 giờ được 50 km.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-116 — Khoảng đệm của gói giờ ✅ GĐ1
Gói giờ dùng khoảng đệm **1 giờ**; gói ngày và dài hạn giữ **2 giờ** (BR-109).

Đệm 2 giờ chiếm khoảng 8% thời gian của gói ngày nhưng tới **33%** của gói 4 giờ — mỗi lượt sẽ chiếm
6 giờ lịch và xe chỉ chạy được 4 lượt/ngày thay vì 6. Lượt ngắn tiêu hao ít, xe ít bẩn, thường chưa
cần đi nạp nhiên liệu.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-117 — Nhiên liệu của gói giờ ✅ GĐ1
Gói giờ **không áp dụng "nhận đầy trả đầy"** và **miễn phí dịch vụ nhiên liệu**. Chỉ tính tiền nhiên
liệu thực tiêu thụ theo công thức BR-415.

Bắt khách thuê 4 giờ chạy 40 km phải ghé cây xăng trước khi trả xe là phiền vô lý, và phí dịch vụ
100.000đ cho chừng đó nhiên liệu là mất cân đối.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-118 — Phí trả trễ của gói giờ ✅ GĐ1
Mỗi giờ trễ tính **20% giá gói giờ** (không phải giá ngày). Trễ quá 4 giờ thì tính thành
**thêm một gói giờ**, không phải thêm một ngày.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-118b — Phí tài xế của gói giờ ✅ GĐ1
Gói giờ có tài xế: phí tài xế = **40% phí ngày** cho 4 giờ đầu, mỗi giờ thêm tính theo mức riêng
trong bảng giá.

Cao hơn tỷ lệ 4/24 vì tài xế mất cả buổi chứ không chỉ 4 tiếng — họ không nhận được chuyến khác xen
vào. Đây là điều chỉnh cho mâu thuẫn giữa BR-219 (phí tài xế theo ngày) và BR-113 (thuê tối thiểu
4 giờ).
*Nguồn: Chief Architect phát hiện khi vẽ domain map, Tech Owner duyệt 24/08/2026.*

### BR-119 — Giờ làm việc chi nhánh ✅ GĐ1
Chi nhánh hoạt động **06:00 – 23:00**. Nhận và trả xe chỉ diễn ra trong khung này.
Lịch xe không cho đặt lượt có giờ nhận hoặc giờ trả nằm ngoài khung.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-120 — Tài khoản và giấy tờ khách ✅ GĐ1
- Đăng ký tài khoản chỉ cần **số điện thoại**. **Một số điện thoại = một tài khoản** (duy nhất).
- CCCD và giấy phép lái xe **không bắt buộc lúc đăng ký**, bổ sung sau được.
- Nhưng **bắt buộc phải có và đã xác minh mới đặt được xe** (BR-106).

Cách này giữ đăng ký nhẹ để không mất khách ở bước đầu, mà vẫn chặn đúng chỗ cần chặn.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-121 — Giới hạn thời điểm đặt xe ✅ GĐ1
| Giới hạn | Nhận tại chi nhánh | Giao tận nơi |
|---|---|---|
| **Sớm nhất** trước giờ nhận | 1 giờ | 3 giờ |
| **Xa nhất** trong tương lai | 6 tháng | 6 tháng |

Giới hạn sớm nhất để chi nhánh kịp lấy xe, kiểm tra, làm thủ tục — và kịp lái tới chỗ khách nếu giao
tận nơi. Để ngắn (1 giờ) có chủ đích: khách thuê gói 4 giờ thường quyết định gấp, và đặt được xe cho
một tiếng nữa chính là điểm mạnh của gói đó.

Giới hạn xa nhất vì bảng giá và tình trạng xe quá 6 tháng là phỏng đoán, và khoá lịch quá lâu cho
một người có thể đã quên là lãng phí.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-122 — Đơn đóng băng giá tại thời điểm đặt ✅ GĐ1
Tổng tiền của một đơn được **chốt tại thời điểm khách đặt**. Bảng giá thay đổi sau đó không ảnh
hưởng tới đơn đã tạo — cùng nguyên tắc với hoa hồng (BR-208) và tham số cấu hình (BR-225).

**Báo giá có hạn dùng 15 phút** (tham số BR-225). Đặt xe bằng báo giá đã hết hạn bị từ chối
(`QUOTE_EXPIRED`) — khách xin báo giá lại. Hạn ngắn để khách không đặt được theo một giá đã cũ; đủ dài
để khách điền xong thông tin.
*Nguồn: nguyên tắc nhất quán với BR-208, BR-225.*

### BR-123 — Xoá tài khoản khách ✅ GĐ1
Khách được yêu cầu xoá tài khoản: tài khoản ngừng đăng nhập được và không còn xuất hiện trong hệ thống.

Nhưng **hồ sơ bằng chứng của các đơn đã hoàn tất được giữ nguyên** — hợp đồng, ảnh CCCD, ảnh GPLX,
biên bản bàn giao. Đó là bằng chứng pháp lý cho phạt nguội (BR-702) và tranh chấp lỗi (BR-426),
và pháp luật cho phép giữ dữ liệu để thực hiện nghĩa vụ pháp lý.

**Không cho xoá** khi còn đơn đang chạy hoặc còn khoản chưa thanh toán.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-124 — Đổi số điện thoại ✅ GĐ1
Số điện thoại là khoá duy nhất của tài khoản (BR-120), nên phải có đường đổi: xác minh OTP số mới,
lịch sử đơn giữ nguyên, số cũ được giải phóng sau một khoảng thời gian cấu hình được.

Không có đường này thì khách đổi số sẽ tạo tài khoản mới, và bạn mất toàn bộ lịch sử của họ —
gồm cả lịch sử vi phạm (BR-707).
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-125 — Đầu vào bắt buộc của tìm kiếm ✅ GĐ1
Bốn thứ sau **không phải bộ lọc tuỳ chọn** mà là đầu vào bắt buộc:

| Đầu vào | Vì sao bắt buộc |
|---|---|
| Vị trí + bán kính | Không có thì không biết xe nào gần |
| **Khoảng thời gian thuê** | Không có thì không biết xe nào còn trống |
| Gói thuê: giờ / ngày / tháng | Quyết định cách tính giá |
| Tự lái hay có tài xế | Quyết định xe nào đủ điều kiện hiển thị |

Hiển thị một chiếc xe mà chưa biết nó có rảnh trong khoảng khách cần là hứa suông.

**Bán kính:** khách chọn; mặc định **10 km**, tối đa **30 km** — khớp mốc "không nhận giao" của BR-412.
Hai giá trị nằm ở cấu hình ứng dụng: không đóng cứng, nhưng không cần lịch sử hiệu lực (BR-225) vì
không ảnh hưởng đơn đã tạo.

**Hỗ trợ theo lát cắt (ADR-0012):** lát cắt 1 chỉ hỗ trợ **tự lái**, gói **giờ** và **ngày**, **nhận
tại chi nhánh**. Có tài xế (lát cắt 4), gói tháng (lát cắt 5) và giao tận nơi (cần phân công nhân
viên giao xe) bị **từ chối rõ ràng là chưa hỗ trợ** — không lặng lẽ bỏ qua. Lặng lẽ bỏ qua thì khách
tưởng đang xem xe có tài xế trong khi thực ra là xe tự lái.

**Phải nhất quán với lúc đặt:** tìm kiếm áp đúng những điều kiện mà bước đặt xe sẽ áp — khoảng đệm
(BR-109, BR-116), tối thiểu 4 giờ (BR-113), giờ chi nhánh (BR-119), cửa sổ đặt (BR-121). Khoảng thuê
vi phạm thì báo lỗi ngay ở tìm kiếm, không hiện xe rồi để khách bị từ chối ở bước cuối.
*Nguồn: Tech Owner duyệt 24/08/2026. Làm rõ 04/10/2026.*

### BR-126 — Bộ lọc tìm kiếm ✅ GĐ1
**Giai đoạn 1:** số chỗ (4/5/7/16) · hộp số (sàn/tự động) · **loại nhiên liệu** · khoảng giá ·
cách nhận xe (tại chi nhánh / giao tận nơi) · **miễn thế chấp** · hãng và dòng xe.

**Thêm sau:** năm sản xuất · đánh giá tối thiểu · tiện nghi (camera hành trình, camera lùi, camera
360, cảm biến lùi, cửa sổ trời, ETC, ghế trẻ em, giá nóc).

**Sắp xếp:** gần nhất (mặc định) · giá thấp đến cao · đánh giá cao nhất.

Hai bộ lọc cần đặt nổi bật:
- **Loại nhiên liệu** — xe điện có giới hạn quãng đường và chuyện sạc dọc đường; khách đi xa chủ động
  tránh, khách chạy phố lại thích vì rẻ. Không phải bộ lọc phụ ở thị trường Việt Nam.
- **Miễn thế chấp** — thế chấp 15–40 triệu là rào cản lớn nhất với khách thuê lần đầu (BR-110).

**Không được có bộ lọc theo loại sở hữu.** Nó vi phạm BR-112 và tạo cảm giác hai hạng xe. Cái khách
thật sự quan tâm là *miễn thế chấp* và *đánh giá* — lọc theo tính chất khách quan tâm, không lọc
theo nhãn sở hữu.

**Giá hiển thị trong kết quả tìm kiếm** là **tổng tiền thuê xe cho đúng khoảng khách chọn**, đã tính
loại ngày (BR-218), cách tính phần giờ lẻ (BR-235) và làm tròn (BR-236). **Không cộng phí bảo hiểm**
— bảo hiểm là dòng riêng trong báo giá (BR-231), khách thấy đủ tổng trước khi đặt. Không hiện "giá từ X/ngày" —
con số đó sai ngay khi khoảng thuê có cuối tuần hay ngày lễ. Lọc và xếp theo giá dùng chính tổng này.

**Thứ tự hiện thực:** lọc và xếp theo **giá** làm cùng `pricing` · xếp theo
**đánh giá** làm khi có đánh giá (BR-901) · lọc **giao tận nơi** làm cùng phân công nhân viên giao xe.
Đây là thứ tự làm, không phải bỏ — cả ba vẫn thuộc giai đoạn 1.
*Nguồn: Tech Owner duyệt 24/08/2026. Làm rõ 04/10/2026, 09/10/2026.*

### BR-127 — Xe hỏng trước giờ giao ✅ GĐ1
Đơn đã `CONFIRMED` mà xe hỏng trước giờ bàn giao:
1. **Ưu tiên đổi sang xe khác** bằng cơ chế BR-305 — nhân viên liên hệ khách, chuyển cọc.
2. Không có xe thay thế → coi như **công ty huỷ** theo BR-303: hoàn 100%, cần `BRANCH_MANAGER` duyệt.
*Nguồn: Chief Architect phát hiện khi vẽ máy trạng thái, Tech Owner duyệt 24/08/2026.*

---

## 2xx — Tiền

> **Ba khoản tách bạch, không được nhầm lẫn** (phân tích §0):
> **cọc** (`deposit`) — trả trước một phần tiền thuê, **không hoàn** ·
> **phần còn lại** (`balance`) — trả khi nhận xe ·
> **thế chấp** (`collateral`) — tài sản bảo đảm, **hoàn 100%** sau khi trả xe.

### BR-201 — Tỷ lệ cọc ✅ GĐ1
| Hình thức thuê | Cọc |
|---|---|
| Thuê ngày | **30%** giá trị đơn |
| Thuê dài hạn theo tháng | **40%** giá trị đơn |
*Nguồn: §10.4, §7.*

### BR-202 — Cọc không hoàn, trừ vào tiền thuê ✅ GĐ1
Cọc là **trả trước một phần tiền thuê**. Khi đơn hoàn tất, cọc được trừ vào tổng tiền thuê.
Cọc chỉ được hoàn trong các trường hợp huỷ đơn quy định ở nhóm 3xx.
*Nguồn: §0.*

### BR-203 — Hai đường thanh toán phần còn lại ✅ GĐ1
Phần còn lại trả **khi nhận xe**, khách chọn một trong hai:
(a) trả trực tiếp cho chủ xe, hoặc (b) chuyển khoản qua app.
*Nguồn: §2.3.*

### BR-204 — Chủ xe xác nhận đã nhận đủ tiền thì chuyến mới bắt đầu ✅ GĐ1
Chuyến chỉ được chuyển sang trạng thái đang diễn ra sau khi **chủ xe xác nhận trên app là đã nhận
đủ phần còn lại**. Với xe công ty, người xác nhận là `BRANCH_STAFF` hoặc `DELIVERY_STAFF`.
*Nguồn: §2.3.*

### BR-205 — Khách xác nhận cùng lúc ✅ GĐ1
Tại thời điểm bàn giao, **khách cũng xác nhận** đã thanh toán đủ phần còn lại, ghi ngay trên biên bản
bàn giao. Không có xác nhận hai phía thì khi tranh chấp số tiền, hệ thống chỉ có lời một bên.
*Nguồn: §2.3, Tech Owner duyệt N24 ngày 24/08/2026.*

### BR-206 — Hoa hồng trừ từ cọc ✅ GĐ2
Hoa hồng của nền tảng được **trừ từ khoản cọc nền tảng đang giữ**. Phần cọc còn lại sau khi trừ hoa
hồng được chi trả cho chủ xe.
*Nguồn: §2.3.*

### BR-207 — Hoa hồng không được vượt tỷ lệ cọc ✅ GĐ2
Mức hoa hồng cấu hình **phải nhỏ hơn hoặc bằng tỷ lệ cọc**. Vi phạm bị chặn **ngay lúc admin cấu
hình**, không phải lúc chi trả. Nếu không, nền tảng không đủ tiền đang giữ để trừ hoa hồng.
*Nguồn: §2.3.*

### BR-208 — Hoa hồng đóng băng theo đơn ✅ GĐ2
Mỗi đơn thuê **lưu mức hoa hồng tại thời điểm tạo đơn** vào chính bản ghi đơn. Admin đổi mức hoa
hồng **không** ảnh hưởng tới đơn đã tạo.
*Nguồn: §2.2.*

### BR-209 — Khi nào yêu cầu thế chấp ✅ GĐ1
| Trường hợp | Thế chấp |
|---|---|
| Xe công ty, thuê giờ | **Không yêu cầu** — như thuê ngày (làm rõ 04/10/2026) |
| Xe công ty, thuê ngày | **Không yêu cầu** |
| Xe công ty, thuê dài hạn | **Bắt buộc** |
| Xe đối tác | **Do đối tác tự quyết** |
*Nguồn: §10.1.*

### BR-210 — Mức thế chấp theo phân khúc ✅ GĐ1
Phân khúc **suy ra từ giá thuê ngày**, không gắn nhãn thủ công:

| Giá thuê ngày | Phân khúc | Thế chấp |
|---|---|---|
| < 1.000.000đ | Phổ thông | 15.000.000đ |
| 1.000.000 – 2.000.000đ | Trung cấp | 25.000.000đ |
| > 2.000.000đ | Cao cấp | 40.000.000đ |
*Nguồn: §7.1, Tech Owner duyệt N21 + N23 ngày 24/08/2026.*

### BR-211 — Thay thế thế chấp bằng tài sản ✅ GĐ1
Khách được thay tiền mặt bằng **xe máy kèm cà vẹt** có giá trị tương đương mức thế chấp yêu cầu.
*Nguồn: §7.1.*

### BR-212 — Hoàn thế chấp ngay sau khi trả xe ✅ GĐ1
Thế chấp hoàn **100%** ngay sau khi khách trả xe và hoàn tất kiểm tra bàn giao.
**Không giữ lại chờ phạt nguội.**
*Nguồn: §10.1, §11.3.*

### BR-213 — Xác nhận thanh toán thủ công phải chống trùng ✅ GĐ1
Giai đoạn hiện tại, nhân viên xác nhận đã nhận tiền bằng tay. Thao tác này là **ghi tiền**, nên bắt
buộc: idempotent (xác nhận hai lần không nhân đôi), ghi audit đầy đủ (ai, khi nào, số tiền), và
không cho phép xác nhận lại một khoản đã xác nhận.
*Nguồn: §8.*

### BR-214 — Khách phải đăng ký tài khoản ngân hàng ✅ GĐ1
Khách phải đăng ký sẵn số tài khoản ngân hàng trong app. Mọi khoản hoàn tiền chuyển về tài khoản này.
*Nguồn: §8, §10.5.*

### BR-215 — Thời hạn hoàn tiền ✅ GĐ1
Mọi khoản hoàn (cọc và thế chấp) chuyển trong vòng **3 ngày làm việc** về tài khoản đã đăng ký.
*Nguồn: §10.5.*

### BR-216 — Không tin số tiền do client gửi ✅ GĐ1
Server **luôn tính lại** mọi số tiền từ dữ liệu của chính mình. Số tiền client gửi lên chỉ dùng để
đối chiếu; lệch thì từ chối giao dịch.
*Nguồn: nguyên tắc kỹ thuật bắt buộc, §8.*

### BR-217 — Quản lý chi nhánh xác nhận thanh toán thay ✅ GĐ1
Khi chủ xe hoặc nhân viên quên thao tác xác nhận đã nhận tiền (BR-204) và khách đang bị chặn không
lấy được xe, `BRANCH_MANAGER` được quyền **xác nhận thay**.

Mọi lần xác nhận thay đều ghi audit riêng, nêu rõ ai xác nhận thay cho ai và vì lý do gì.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-218 — Giá thuê thay đổi theo loại ngày ✅ GĐ1
Giá thuê xe công ty phân biệt theo **loại ngày**: ngày thường · cuối tuần · lễ tết.
Cuối tuần và lễ tết có giá cao hơn.

Giá của một đơn tính theo **từng ngày trong khoảng thuê**, không lấy một giá duy nhất nhân số ngày —
một chuyến từ thứ Sáu tới Chủ nhật có ngày thường lẫn ngày cuối tuần.

**Ba loại ngày, tính trên giá ngày của chính chiếc xe (BR-234):**

| Loại ngày | Ngày nào | Giá |
|---|---|---|
| Ngày thường | Còn lại | Giá ngày |
| Cuối tuần | Thứ Bảy, Chủ nhật | Giá ngày **+20%** |
| Lễ tết | Lịch nghỉ chính thức nhà nước công bố mỗi năm, **kể cả ngày nghỉ bù** | Giá ngày **+50%** |

Cả bốn — định nghĩa cuối tuần, hai mức tăng, danh sách ngày lễ — là tham số cấu hình (BR-225), không
viết cứng. Danh sách ngày lễ là **dữ liệu** cập nhật mỗi năm; không thêm "ngày cao điểm" quanh Tết.

**Mỗi ngày thuê thuộc loại nào:** khoảng thuê chia thành từng **khối 24 giờ tính từ giờ nhận xe**. Mỗi
khối lấy loại ngày theo **ngày lịch của giờ bắt đầu khối**, giờ Việt Nam. Ví dụ nhận thứ Sáu 10:00, trả
Chủ nhật 10:00: khối 1 bắt đầu thứ Sáu (ngày thường), khối 2 bắt đầu thứ Bảy (cuối tuần).

**Cuối tuần trùng ngày lễ:** lấy mức **cao hơn** (+50%), không cộng dồn.
*Nguồn: Tech Owner chốt 24/08/2026. Làm rõ 09/10/2026 — trước đó ghi "chưa chốt" dù BR-225 đã có giá
trị khởi điểm cho cả ba.*

### BR-219 — Phí thuê có tài xế ✅ GĐ1
Tính **theo ngày**, cộng thẳng vào tổng đơn. Khách thấy một con số duy nhất, và cọc 30% tính trên
tổng đã bao gồm phí tài xế.

**Chi phí ăn ở của tài xế khi chạy đường dài:**
- Tài xế là nhân viên công ty → **công ty cấp**, không thu thêm của khách.
- Tài xế của đối tác → **đối tác tự lo**.

⚠️ Chưa chốt: mức đồng/ngày. Xem thêm BR-223 về phụ phí qua đêm.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-223 — Phụ phí qua đêm cho chuyến có tài xế ✅ GĐ1
Một mức phí tài xế phẳng theo ngày sẽ **lỗ trên chuyến đường dài**: chuyến trong thành phố tài xế
về nhà ngủ, còn chuyến Sài Gòn–Đà Lạt 3 ngày thì công ty phải trả chỗ ở cho tài xế mỗi đêm.

Đề xuất: thu **phụ phí qua đêm** cho mỗi đêm chuyến đi buộc tài xế phải ở lại ngoài địa bàn.
Không có khoản này, mọi chuyến đường dài đều ăn vào lợi nhuận và không ai phát hiện ra cho tới khi
xem báo cáo cuối quý.
*Nguồn: Chief Architect, Tech Owner duyệt 24/08/2026.*

### BR-220 — Thuê dài hạn trả theo tháng ✅ GĐ1
Sau khi đặt cọc 40%, phần còn lại chia thành **các kỳ thanh toán hàng tháng**.

Lý do: khách thuê dài hạn đã phải bỏ ra cọc 40% cộng thế chấp; bắt trả hết một lần sẽ lọc mất phần
lớn khách.

⚠️ Chưa chốt: khách trễ kỳ thì xử lý thế nào — gia hạn bao lâu, có thu hồi xe không.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-226 — Hoá đơn VAT ✅ GĐ1
Hệ thống lưu thông tin xuất hoá đơn của khách (mã số thuế, tên đơn vị, địa chỉ) và đánh dấu đơn nào
cần hoá đơn. **Kế toán xuất hoá đơn thủ công** — nhất quán với việc thanh toán đang thủ công (BR-213).

Khách doanh nghiệp không có hoá đơn thì không hạch toán được và sẽ không thuê. Đây lại đúng là nhóm
thuê dài hạn và thuê có tài xế nhiều nhất.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-227 — Mã giảm giá ✅ GĐ1
Hệ thống hỗ trợ mã giảm giá từ giai đoạn 1. Mỗi mã có: loại giảm (**phần trăm** hoặc **số tiền cố
định**), giá trị, thời gian hiệu lực, **tổng số lượt dùng**, và **số lượt mỗi khách**.

**Không cho chồng mã** — một đơn dùng tối đa một mã. Chồng mã là nguồn gốc của những đơn giảm 90%
mà không ai cố ý tạo ra.

Trừ hạn mức phải là thao tác nguyên tử — nhiều khách cùng dùng mã còn 1 lượt thì chỉ một người được.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-228 — Mã giảm giá chỉ áp lên tiền thuê xe ✅ GĐ1
Mã giảm giá áp lên **tiền thuê xe**. **Không** áp lên phí tài xế (BR-219) và phí giao xe (BR-412) —
hai khoản đó là chi phí thật (lương tài xế, nhân công đi giao), giảm giá trên chúng là cắt thẳng vào
chi phí chứ không phải vào biên lợi nhuận.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-229 — Nền tảng chịu toàn bộ chi phí khuyến mãi ✅ GĐ2
Hoa hồng đối tác tính trên **giá trước giảm**. Chủ xe nhận đủ như thể không có khuyến mãi;
phần giảm do **nền tảng chịu hoàn toàn**.

Khuyến mãi là quyết định của nền tảng, chủ xe không được hỏi ý kiến. Bắt họ gánh một chương trình
họ không tham gia quyết định là bất công và sẽ khiến họ rời sàn.

Cùng nguyên tắc với BR-302 (bồi thường khi huỷ) và BR-422 (ứng trả tiền sạc): **nền tảng đứng ra
chịu, đó là lý do người ta ở lại.**
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-230 — Giá thuê dài hạn: chiết khấu theo độ dài ✅ GĐ1
Giá thuê dài hạn = **giá ngày × số ngày**, trừ **chiết khấu theo bậc độ dài** (ví dụ 7+ ngày giảm x%,
30+ ngày giảm y%). Bậc chiết khấu là tham số cấu hình được (BR-225).

Một bảng giá gốc duy nhất thay vì hai bảng song song — tránh việc hai bảng lệch nhau, và mở được cả
gói thuê tuần mà không cần bảng mới.

⚠️ Chưa chốt: các bậc chiết khấu cụ thể.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-231 — Phí bảo hiểm tách thành dòng riêng ✅ GĐ1
Phí bảo hiểm theo chuyến (BR-601) **bắt buộc**, và hiển thị thành **một dòng riêng** trong báo giá
lẫn hoá đơn — không gộp vào giá thuê.

Bảo hiểm là lý do khách dám thuê xe tự lái (BR-602). Giấu nó vào giá thuê là giấu mất điểm bán hàng
mạnh nhất, và đi ngược nguyên tắc minh bạch đã áp dụng cho tiền năng lượng (BR-419).

**Mức phí:** cách tính (cố định mỗi ngày tính tiền, hoặc % tiền thuê) và giá trị là **tham số cấu hình
bắt buộc khai báo** (BR-225), **chưa có giá trị khởi điểm** — chờ làm việc với nhà bảo hiểm. Thiếu
tham số thì ứng dụng **không khởi động**, thay vì tự điền một con số giả định rồi báo giá sai cho khách.
*Nguồn: Tech Owner chốt 24/08/2026. Làm rõ 09/10/2026.*

### BR-232 — Khách chuyển khoản thừa ✅ GĐ1
Phần thừa được ghi nhận và **hoàn về tài khoản đã đăng ký trong 3 ngày làm việc** (như BR-215).

Không tự chuyển thành số dư giữ hộ: giữ tiền của khách mà họ không yêu cầu là chuyện phiền cả về
kế toán lẫn niềm tin.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-233 — Thu hồi xe khi hợp đồng bị đình chỉ ✅ GĐ1
Hợp đồng dài hạn `SUSPENDED` (BR-221) mà khách không trả xe thì chuyển sang trạng thái
**thu hồi tài sản**: công ty chủ động thu hồi, dùng GPS định vị (BR-705), theo đường pháp lý đã ghi
trong hợp đồng.

Trạng thái này **cần `ADMIN` kích hoạt** — nó là bước đối đầu trực tiếp với khách và không được để
xảy ra do một thao tác nhầm ở chi nhánh.
*Nguồn: Chief Architect phát hiện khi vẽ máy trạng thái, Tech Owner duyệt 24/08/2026.*

### BR-234 — Mỗi xe có giá ngày riêng ✅ GĐ1
Mỗi xe có một **giá ngày** — giá thuê một ngày thường — do admin nhập. Mọi khoản tính theo giá ngày đều
lấy giá của **chính chiếc xe đó**: tăng giá cuối tuần và lễ tết (BR-218), gói giờ (BR-114), phí trả trễ
(BR-408), phân khúc thế chấp (BR-210).

**Bắt buộc có giá ngày trước khi duyệt xe** (BR-010): xe chưa có giá thì không lên sàn, vì khách tìm
thấy một chiếc xe không báo được giá là hứa suông (BR-125). Đổi giá sau đó không ảnh hưởng đơn đã tạo
(BR-122).
*Nguồn: suy ra từ BR-408 ("giá thuê ngày của chính chiếc xe đó") và BR-210; Tech Owner duyệt 09/10/2026.*

### BR-235 — Gói ngày: tính theo khối 24 giờ, phần lẻ như trả trễ ✅ GĐ1
Gói ngày tính theo từng **khối 24 giờ tính từ giờ nhận xe**; mỗi khối theo loại ngày của nó (BR-218).

Phần lẻ sau khối 24 giờ cuối cùng tính **đúng như trả xe trễ** (BR-408): **≤ 4 giờ** thì mỗi giờ 20% giá
ngày; **quá 4 giờ** thì thành thêm một ngày. Giá ngày của phần lẻ theo loại ngày của giờ bắt đầu phần lẻ.

**Tối thiểu một ngày:** khoảng thuê ngắn hơn 24 giờ theo gói ngày tính bằng một khối.

Dùng chung một cách tính cho đặt trước và trả trễ: khách đặt 26 giờ và khách đặt 24 giờ rồi trả trễ
2 giờ trả cùng một số tiền.
*Nguồn: Tech Owner duyệt 09/10/2026.*

### BR-236 — Làm tròn tiền tới 1.000đ ✅ GĐ1
Mọi khoản tiền tính ra số lẻ (tăng giá theo loại ngày, gói giờ, phần giờ lẻ…) được **làm tròn từng dòng
tới 1.000đ**, từ 500đ trở lên làm tròn lên. Tổng là tổng các dòng đã làm tròn — khách cộng tay các
dòng trên báo giá phải ra đúng tổng.
*Nguồn: Tech Owner duyệt 09/10/2026.*

### BR-237 — Giờ lẻ làm tròn lên ✅ GĐ1
Mọi quy tắc tính "**mỗi giờ**" — giờ vượt của gói giờ (BR-114), trả trễ gói giờ (BR-118), trả trễ gói
ngày (BR-408), phần giờ lẻ của gói ngày (BR-235) — **làm tròn lên theo giờ**: lẻ 1 phút cũng tính 1 giờ.

Áp như nhau cho đặt trước và trả trễ. Không có thời gian ân hạn — nếu muốn nương tay cho khách trễ vài
phút, đó là một con số phải chốt riêng.
*Nguồn: Tech Owner duyệt 09/10/2026.*

---

## 3xx — Huỷ đơn và đổi xe

### BR-301 — Khách huỷ: thang ba mốc ✅ GĐ1
Tính theo **giờ nhận xe**:

| Thời điểm huỷ | Hoàn cọc |
|---|---|
| Trước **1 tuần** | **100%** |
| **1 tuần → 24 giờ** | **60%** (giữ lại 40% phí huỷ) |
| Trong vòng **24 giờ** | **0%** — mất toàn bộ cọc |
*Nguồn: §10.5.*

### BR-302 — Đối tác huỷ ✅ GĐ2
Đối tác huỷ đơn thì: bị **trừ sao đánh giá** **và** phải **bồi thường khách 40% giá trị chuyến**.
Khách được hoàn cọc **100%**.

Chế tài tiền là bắt buộc chứ không chỉ trừ sao: mất một sao rẻ hơn nhiều so với chênh lệch tiền thuê
khi có khách khác trả cao hơn. Chỉ tiền mới cân được tiền.
*Nguồn: §10.5, Tech Owner duyệt 24/08/2026.*

### BR-303 — Công ty huỷ đơn xe công ty ✅ GĐ1
Đơn xe công ty chỉ được huỷ khi có **`BRANCH_MANAGER` duyệt** kèm **lý do chính đáng** được ghi lại.
Khách hoàn cọc **100%**.
*Nguồn: §10.5.*

### BR-304 — Huỷ đơn luôn giải phóng chỗ ✅ GĐ1
Mọi trường hợp huỷ đơn đều giải phóng chỗ trên lịch xe. Không có ngoại lệ.
*Nguồn: §10.4, §10.7.*

### BR-305 — Đổi xe giữ nguyên cọc ✅ GĐ1
Khi khách muốn huỷ, nhân viên được phép liên hệ tìm hiểu lý do và tư vấn xe khác. Nếu khách đồng ý,
nhân viên **chuyển cọc sang đơn mới** thay vì huỷ và hoàn tiền.
*Nguồn: §10.7.*

### BR-306 — Xử lý chênh lệch khi đổi xe ✅ GĐ1
| Tình huống | Xử lý |
|---|---|
| Cọc xe mới **cao hơn** | Khách bù phần chênh |
| Cọc xe mới **thấp hơn** | Phần dư **trừ vào phần còn lại** khi nhận xe |
| Cọc xe mới **bằng** | Không phát sinh |
*Nguồn: §10.7, Tech Owner chốt N17 ngày 24/08/2026.*

### BR-307 — Trạng thái riêng cho đơn đã đổi xe ✅ GĐ1
Đơn cũ mang trạng thái **"đã chuyển đổi"**, **không phải "đã huỷ"**. Ghi là huỷ sẽ làm sai tỷ lệ huỷ
trong báo cáo và mất khả năng đo hiệu quả của chính nghiệp vụ này.
*Nguồn: §10.7.*

### BR-308 — Thứ tự an toàn khi đổi xe ✅ GĐ1
Phải **giữ được chỗ trên xe mới trước khi nhả chỗ xe cũ**. Nếu giữ chỗ xe mới thất bại, đơn cũ giữ
nguyên. Không được để khách mất cả hai.
*Nguồn: §10.7.*

### BR-309 — Chuyển cọc phải chống trùng ✅ GĐ1
Thao tác chuyển cọc là **ghi tiền**: idempotent, và ghi audit gồm ai chuyển, từ đơn nào sang đơn nào,
chênh lệch bao nhiêu.
*Nguồn: §10.7.*

### BR-221 — Xử lý trễ kỳ thanh toán thuê dài hạn ✅ GĐ1
Thuê dài hạn là quan hệ **tín dụng**: chiếc xe nằm trong tay khách suốt kỳ, còn tiền thì trả dần.
Nếu khách ngừng trả, tài sản của bạn đang ở ngoài đường.

Đề xuất thang phản ứng tăng dần:

| Thời điểm | Hành động |
|---|---|
| Trước hạn 3 ngày | Nhắc tự động qua app và SMS |
| Đúng ngày đến hạn | Nhắc lần nữa |
| Trễ 1–3 ngày | **Ân hạn** — nhắc, chưa phạt. Người ta quên, và chuyển khoản có thể chậm |
| Trễ 4–7 ngày | Tính **phí trễ hạn theo ngày**. Nhân viên gọi điện trực tiếp |
| Trễ trên 7 ngày | **Đình chỉ hợp đồng**, yêu cầu trả xe. Trừ thế chấp để bù kỳ chưa trả |
| Không trả xe | Xử lý theo hợp đồng và đường pháp lý. GPS (BR-705) hỗ trợ định vị |

**Ân hạn 1–3 ngày là có chủ đích.** Phạt ngay ngày đầu sẽ phạt nhầm phần lớn khách tử tế chỉ quên
hoặc bị ngân hàng xử lý chậm, và làm hỏng quan hệ với đúng nhóm khách giá trị nhất.

*Nguồn: Chief Architect tư vấn, Tech Owner duyệt 24/08/2026.*

### BR-222 — Thế chấp phải đủ bù ít nhất một kỳ ✅ GĐ1
Mức thế chấp theo bậc (BR-210) hiện **tình cờ khớp** với khoảng một tháng tiền thuê dài hạn ở mỗi
phân khúc. Đây là quan hệ nên **giữ có chủ đích**, không phải để nó trôi.

Lý do: BR-221 dùng thế chấp để bù kỳ chưa trả khi đình chỉ hợp đồng. Nếu thế chấp nhỏ hơn một kỳ
thì cơ chế đó không đủ che, và mỗi vụ đình chỉ đều lỗ.

Khi sửa bảng thế chấp hoặc bảng giá thuê tháng, phải kiểm lại quan hệ này.
*Nguồn: Chief Architect, Tech Owner duyệt 24/08/2026.*

### BR-224 — Trạng thái quá hạn phải chặn nghiệp vụ khác ✅ GĐ1
Hợp đồng dài hạn đang có kỳ quá hạn thì khách **không được**: gia hạn hợp đồng, đặt thêm xe mới,
hay nhận ưu đãi. Trạng thái quá hạn phải là dữ liệu hệ thống tra được tức thời, không phải thứ kế
toán phát hiện cuối tháng.
*Nguồn: Chief Architect, Tech Owner duyệt 24/08/2026.*

### BR-225 — Tham số kinh doanh phải cấu hình được ✅ GĐ1
Các tham số dưới đây **không được đóng cứng trong code**. Chúng là cấu hình admin chỉnh được, có
hiệu lực theo thời gian, và mỗi đơn thuê **đóng băng** giá trị tại thời điểm tạo — cùng nguyên tắc
với hoa hồng (BR-208).

| Tham số | Giá trị khởi điểm |
|---|---|
| Phí tài xế (BR-219) | 500.000đ/ngày |
| Phụ phí qua đêm (BR-223) | 300.000đ/đêm |
| Định nghĩa cuối tuần (BR-218) | Thứ Bảy + Chủ nhật |
| Mức tăng giá cuối tuần (BR-218) | +20% |
| Mức tăng giá lễ tết (BR-218) | +50% |
| Danh sách ngày lễ (BR-218) | Theo lịch nghỉ chính thức hằng năm — **là dữ liệu, không phải code** |
| Phí dịch vụ xe xăng thiếu nhiên liệu (BR-409) | 100.000đ |
| Phí dịch vụ xe điện thiếu pin (BR-414) | 200.000đ |
| Phí trễ hạn thuê dài hạn (BR-221) | 0,5%/ngày trên số tiền kỳ |
| Ngưỡng tái phạm (BR-707) | 3 lần/12 tháng cảnh báo · 5 lần hạn chế thuê |
| Trần ứng trả chi phí năng lượng (BR-422) | 500.000đ/chuyến |
| Số ngày báo trước khi giấy tờ hết hạn (BR-017) | 30 ngày |
| **Thời hạn giữ chỗ** (BR-103) | 1 tiếng |
| Hạn dùng báo giá (BR-122) | 15 phút |
| Phí bảo hiểm theo chuyến — cách tính và giá trị (BR-231) | **Chưa có — bắt buộc khai báo**, thiếu thì ứng dụng không khởi động |

Lý do: Tech Owner chưa có xe, chưa có khách, chưa có dữ liệu thị trường — mọi con số hiện tại là
phỏng đoán có tham chiếu. Đóng cứng phỏng đoán vào code nghĩa là mỗi lần sửa phải chờ một đợt
phát hành. Danh sách ngày lễ càng rõ: mỗi năm lịch nghỉ khác nhau, không ai muốn phát hành lại app
vì Tết rơi vào ngày khác.

**Không thuộc bảng này — tham số vận hành.** Nhịp chạy job dọn giữ chỗ quá hạn (BR-103), khởi điểm
**30 giây**, cũng không đóng cứng trong code, nhưng nằm ở **cấu hình ứng dụng** và **không** có hiệu
lực theo thời gian. Nó quyết định máy chạy thế nào, không quyết định khách trả bao nhiêu hay được
gì — câu hỏi "lúc khách đặt đơn thì nhịp dọn là bao nhiêu" không có ý nghĩa nghiệp vụ.

**Tạm thời, cho tới khi có module `config`:** thời hạn giữ chỗ đọc từ cấu hình ứng dụng. Admin chưa
chỉnh được trên màn hình quản trị, chưa có lịch sử hiệu lực, và đổi giá trị phải khởi động lại hệ
thống. Mỗi chỗ giữ vẫn đóng băng hạn của nó tại lúc tạo, nên đổi cấu hình không ảnh hưởng chỗ đang giữ.
*Nguồn: Tech Owner duyệt 24/08/2026. Làm rõ 30/09/2026.*

---

## 4xx — Bàn giao, sử dụng và trả xe

### BR-401 — Giới hạn km tính gộp toàn chuyến ✅ GĐ1
Giới hạn km của một đơn = `giới_hạn_ngày × số_ngày_thuê`, áp dụng cho **toàn bộ chuyến**,
**không** kiểm theo từng ngày.

Ví dụ: thuê 5 ngày với giới hạn 300 km/ngày ⇒ được chạy tối đa 1.500 km trong cả chuyến.
Chạy 400 km trong ngày đầu **không** bị phạt nếu tổng cả chuyến không vượt 1.500 km.
*Nguồn: §10.2.*

### BR-402 — Giới hạn km xe công ty ✅ GĐ1
Xe công ty: **300 km/ngày**.
*Nguồn: §10.2.*

### BR-402b — Giới hạn km gói tháng ✅ GĐ1
Gói thuê tháng dùng **cùng công thức** với các gói khác (BR-401): 300 km × số ngày, tức 9.000 km cho
30 ngày. Giữ công thức chung cho nhất quán; mức giới hạn là tham số cấu hình được (BR-225) nên có
thể đặt riêng cho gói tháng về sau mà không phải sửa code.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-403 — Giới hạn km xe đối tác ✅ GĐ2
Đối tác tự đặt giới hạn km/ngày cho xe của mình. Cách tính gộp theo BR-401 vẫn áp dụng.
*Nguồn: §10.2.*

### BR-404 — Trần phụ phí vượt km ✅ GĐ2
```text
giá trị ngầm mỗi km = giá thuê 1 ngày ÷ giới hạn km 1 ngày
trần phụ phí vượt   = 2 × giá trị ngầm mỗi km
```
*Nguồn: §10.3, Tech Owner duyệt N4 ngày 23/08/2026.*

### BR-405 — Chặn trần lúc đăng tin ✅ GĐ2
Mức phụ phí vượt km do đối tác đặt bị kiểm tra ngay khi **đăng tin/cập nhật giá**, không phải lúc
tính tiền. Vượt trần thì không lưu được.
*Nguồn: §10.3.*

### BR-406 — Ghi ODO hai lần ✅ GĐ1
Số km trên đồng hồ được ghi tại **thời điểm giao xe** và **thời điểm nhận lại xe**. Hiệu hai số là
căn cứ duy nhất để tính vượt giới hạn km.
*Nguồn: §10.2.*

### BR-407 — Biên bản bàn giao bất biến ✅ GĐ1
Biên bản bàn giao gồm ảnh tình trạng xe, ODO, mức nhiên liệu, mốc thời gian, và chữ ký hai bên.
Sau khi ký, biên bản **không được sửa, không được xoá, không được ghi đè**. Chỉ được bổ sung phiên
bản mới kèm lý do.

Đây là **bằng chứng pháp lý**, không phải giấy tờ hành chính — xem BR-701.
*Nguồn: §11.2.*

### BR-408 — Phí trả xe trễ giờ ✅ GĐ1
Mỗi giờ trễ tính **20% giá thuê ngày** của chính chiếc xe đó. Trễ **quá 4 giờ** thì tính thành
**thêm một ngày thuê**.

Dùng công thức thay vì số cố định để tự co giãn theo phân khúc, và để mốc 4 giờ khớp: trễ 4 giờ là
80% giá ngày, vừa dưới ngưỡng chuyển thành nguyên ngày. Không có điểm nào khách trễ ít lại phải trả
nhiều hơn trễ nhiều.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-409 — Nhiên liệu: xe động cơ đốt trong ✅ GĐ1
Xe xăng và xe dầu: **giao đầy bình, trả đầy bình**. Trả thiếu thì tính **tiền nhiên liệu theo giá
thị trường + phí dịch vụ 100.000đ**.

Phí dịch vụ tồn tại để khách không coi việc trả xe cạn nhiên liệu là chuyện bình thường.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-410 — Loại nhiên liệu là thuộc tính của xe ✅ GĐ1
Mỗi xe có `fuel_type` ∈ `{ PETROL, DIESEL, ELECTRIC, HYBRID }`. Chính sách nhiên liệu khi giao/trả xe
được **phân giải theo `fuel_type`**, không phải điều kiện rải rác trong luồng bàn giao.

Xe điện không thể áp dụng "trả đầy" như xe xăng vì nạp đầy mất hàng giờ. Quy tắc riêng cho xe điện
xem BR-411 (chưa chốt).
*Nguồn: Tech Owner nêu yêu cầu xe điện 24/08/2026.*

### BR-411 — Nhiên liệu: xe điện ✅ GĐ1
Xe điện **giao 100% pin, trả tối thiểu 70%**.

Không áp dụng "trả đầy" như xe xăng vì nạp đầy mất hàng giờ — bắt khách trả 100% nghĩa là bắt họ
ngồi ở trạm sạc trước khi trả xe. Ngưỡng 70% để lại đệm cho chi nhánh nạp lại.
*Nguồn: Tech Owner chọn phương án A ngày 24/08/2026.*

### BR-414 — Hai khoản tách bạch khi quyết toán năng lượng ✅ GĐ1
Khi trả xe, hệ thống tính **hai khoản riêng biệt**, không gộp:

| Khoản | Khi nào thu | Bản chất |
|---|---|---|
| **Tiền năng lượng** | **Luôn luôn**, theo lượng tiêu thụ thật | Hoàn lại chi phí cho chủ xe |
| **Phí dịch vụ** | **Chỉ khi** trả dưới ngưỡng (70% pin / không đầy bình) | Chế tài, không phải tiền năng lượng |

Gộp hai khoản làm một khiến khách không phân biệt được đâu là tiền họ đã dùng, đâu là tiền phạt —
và đó chính là chỗ sinh tranh cãi.
*Nguồn: Tech Owner yêu cầu minh bạch giá cả, 24/08/2026.*

### BR-415 — Công thức tiền năng lượng ✅ GĐ1
Khách trả cho phần năng lượng **mà chủ xe đã ứng tiền**:

```text
năng lượng khách phải trả = E_chủ_xe_nạp_trong_chuyến + (E_giao − E_trả)
tiền năng lượng           = năng lượng khách phải trả × đơn giá
```

Một công thức dùng chung cho mọi loại xe, khác nhau ở chỗ **ai ứng tiền nạp nhiên liệu**:

| Loại xe | `E_chủ_xe_nạp_trong_chuyến` | Kết quả |
|---|---|---|
| Xăng / dầu | **0** — khách tự đổ xăng bằng tiền của họ | Khách chỉ trả phần thiếu so với lúc giao |
| Điện (chuẩn VinFast) | Toàn bộ các phiên sạc — trụ sạc tính vào tài khoản chủ xe | Khách trả phần đã sạc **cộng** phần pin còn thiếu |

Công thức này tự đúng trong mọi tình huống, kể cả khi khách trả xe với pin **cao hơn** lúc nhận:
phần pin dư nằm lại trong xe và chủ xe hưởng, nên nó tự động được trừ khỏi số khách phải trả.
Không cần hai nhánh "dư" và "thiếu" riêng.
*Nguồn: Tech Owner yêu cầu tính năng lượng dư thừa hoặc thiếu, 24/08/2026.*

### BR-416 — Chủ xe nhập bản ghi phiên sạc ✅ GĐ2
Chủ xe nhập **tổng kWh** và **tổng số tiền** từ bản ghi phiên sạc trong app của hãng.
**Hệ thống tự tính đơn giá** = tổng tiền ÷ tổng kWh — chủ xe **không** tự khai đơn giá rời.

Lý do: đơn giá tự khai là chỗ có thể thổi phồng. Suy ra từ chính bản ghi thì con số luôn khớp bằng
chứng, và chủ xe không phải làm phép tính nào.

Mỗi bản ghi phải kèm **ảnh chụp hoặc bản xuất từ app của hãng**, lưu bất biến như biên bản bàn giao
(BR-407).
*Nguồn: Tech Owner chốt phương án B, 24/08/2026.*

### BR-417 — Khớp phiên sạc với đơn thuê ✅ GĐ2
Phiên sạc được khớp vào đơn thuê **theo mốc thời gian**, dùng chính cơ chế của BR-701
("xe X thuộc đơn thuê nào tại thời điểm T"). Phiên sạc nằm ngoài mọi khoảng thuê là chi phí của
chủ xe, không tính cho khách.
*Nguồn: §11, Tech Owner chốt 24/08/2026.*

### BR-418 — Quyết toán năng lượng ngay tại thời điểm trả xe ✅ GĐ1
Tiền năng lượng và phí dịch vụ được **quyết toán khi khách trả xe, lúc khách còn có mặt**, không để
sang giai đoạn truy thu sau.

Bản ghi phiên sạc của hãng thường có gần như tức thời, nên chủ xe nhập được ngay tại chỗ. Quyết toán
tại chỗ loại bỏ gần hết rủi ro không đòi được — cùng lý do với việc tra cứu phạt nguội tại thời điểm
nhận lại xe (BR-706).
*Nguồn: Chief Architect, dựa trên phương án B Tech Owner chọn.*

### BR-419 — Minh bạch năng lượng cho cả hai bên ✅ GĐ1
Hệ thống hiển thị bảng tính năng lượng cho **cả khách thuê và chủ xe**, gồm: mức pin/nhiên liệu lúc
giao và lúc trả, các phiên sạc trong chuyến, đơn giá suy ra, năng lượng tiêu thụ, tiền năng lượng,
và phí dịch vụ nếu có — tách bạch theo BR-414.
*Nguồn: Tech Owner yêu cầu minh bạch giá cả, 24/08/2026.*

### BR-412 — Phí giao xe tận nơi ✅ GĐ1
| Khoảng cách từ chi nhánh | Phí |
|---|---|
| ≤ 5 km | Miễn phí |
| > 5 km | 10.000đ/km |
| > 30 km | **Không nhận giao** |
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-413 — Khách trả xe sai chi nhánh ✅ GĐ1
BR-105 bắt buộc trả đúng chi nhánh. Trường hợp ngoại lệ:
- Khách **phải được `BRANCH_MANAGER` chấp thuận trước**, không tự quyết.
- Khách chịu **phí điều chuyển** tính theo khoảng cách giữa hai chi nhánh.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-420 — Nạp nhiên liệu là nghiệp vụ của chi nhánh ✅ GĐ1
Chi nhánh **không có trạm sạc hay cây xăng tại chỗ**. Sau khi khách trả xe, nhân viên chi nhánh
**đưa xe ra trạm sạc của hãng hoặc cây xăng bên ngoài** để nạp lại.

Nhân viên **nhập bản ghi** vào hệ thống: lượng nạp (kWh hoặc lít), số tiền, thời điểm, trạm nào.
Kế toán dùng bản ghi này để tính.

Cùng cấu trúc dữ liệu với bản ghi phiên sạc do chủ xe đối tác nhập (BR-416) — khác nhau chỉ ở ai nhập.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-421 — Chi phí nhiên liệu ghi nhận theo từng xe ✅ GĐ1
Mọi bản ghi nạp nhiên liệu gắn với **một chiếc xe cụ thể**, không gộp theo chi nhánh.

Đây là dữ liệu để tính **lãi lỗ từng xe** — chi phí nhiên liệu là một trong những khoản lớn nhất của
đội xe, và nếu chỉ ghi tổng theo chi nhánh thì không bao giờ biết được chiếc nào đang lỗ.
*Nguồn: Tech Owner nêu yêu cầu kế toán, 24/08/2026.*

### BR-422 — Nền tảng ứng trả chi phí năng lượng không truy thu được ✅ GĐ2
Khi bản ghi phiên sạc xuất hiện **sau khi khách đã trả xe** và không truy thu được từ khách,
**nền tảng ứng trả cho chủ xe**, tối đa **500.000đ mỗi chuyến**. Nền tảng tự chịu trách nhiệm
đòi lại từ khách.

Số tiền mỗi vụ nhỏ và tần suất thấp vì BR-418 đã chặn phần lớn. Đổi lại, chủ xe tin rằng cho thuê
qua sàn thì không bị mất tiền oan — đó là điều giữ chân nguồn cung. Trần 500.000đ để không bị lợi dụng.

Cùng nguyên tắc với BR-302: nền tảng đứng ra bảo vệ bên bị thiệt.
*Nguồn: Tech Owner duyệt khuyến nghị Chief Architect, 24/08/2026.*

### BR-423 — Gia hạn chuyến đang diễn ra ✅ GĐ1
Khách đang thuê được **yêu cầu gia hạn** thêm thời gian. Hệ thống kiểm lịch xe:
- Còn trống (kể cả khoảng đệm BR-109) → cho gia hạn, thu thêm tiền theo bảng giá đang áp dụng.
- Đã có khách đặt tiếp → **từ chối**, yêu cầu trả xe đúng hạn.

Gia hạn là doanh thu thêm không tốn chi phí tìm khách. Nhưng nó **không được phá vỡ BR-104** —
xe đã hứa cho người khác thì phải trả đúng hạn.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-424 — Trả xe sớm không hoàn tiền ✅ GĐ1
Khách trả xe trước hạn **không được hoàn** tiền những ngày/giờ không dùng.

Lý do: xe đã bị khoá lịch cả khoảng đó nên không cho ai thuê được — khách trả sớm không làm bạn bán
được khoảng thời gian đó cho người khác.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-425 — Xe hỏng giữa đường ✅ GĐ1
Xử lý **phân theo lỗi**:

| Nguyên nhân | Cứu hộ | Chuyến đi |
|---|---|---|
| **Lỗi kỹ thuật của xe** | Công ty chịu | Bố trí xe thay, hoặc hoàn tiền phần chưa dùng |
| **Lỗi của khách** (đâm va, đổ nhầm nhiên liệu, đi đường cấm, chở quá tải) | Khách chịu | Khách chịu, chuyến kết thúc |

Đây là ngoại lệ duy nhất của BR-424: hoàn tiền phần chưa dùng **chỉ** khi lỗi thuộc về xe.

⚠️ Chưa chốt: ai xác định lỗi thuộc về ai, và xử lý thế nào khi hai bên không đồng ý.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-426 — Quy trình xác định lỗi khi xe hỏng ✅ GĐ1
BR-425 phân xử theo lỗi. Quy trình xác định lỗi có **ba bậc**:

| Bậc | Khi nào | Ai quyết |
|---|---|---|
| 1 | Nguyên nhân rõ ràng (hết ắc quy do để đèn, lốp mòn nổ, đổ nhầm nhiên liệu) | `BRANCH_STAFF` quyết ngay |
| 2 | Không rõ | **Gara hoặc đơn vị kỹ thuật độc lập** kết luận. Công ty ứng chi phí giám định |
| 3 | Hai bên không đồng ý với kết luận bậc 2 | **Hội đồng nội bộ** (`OPERATIONS_MANAGER` + `ACCOUNTANT`) xem toàn bộ bằng chứng |

Quyết định ở bậc 3 phải ghi hồ sơ và **thông báo lý do cho khách bằng văn bản**.

**Ngưỡng bỏ qua giám định:** thiệt hại dưới **2.000.000đ** thì chi nhánh tự quyết, không cần gara —
chi phí giám định có khi vượt giá trị đang tranh chấp. Con số này trùng mức miễn thường bảo hiểm
(BR-602) nên khách chỉ phải nhớ một con số.

**Nguyên tắc 1 — bằng chứng ngang nhau thì nghiêng về khách.** Công ty nắm biên bản bàn giao, GPS,
camera hành trình, lịch bảo dưỡng; khách không có gì. Trong thế chênh lệch đó, đổ lỗi cho khách luôn
thắng trước mắt và luôn thua về dài hạn.

**Nguyên tắc 2 — lịch sử bảo dưỡng là bằng chứng bảo vệ công ty.** Xe được bảo dưỡng đúng lịch và có
hồ sơ đầy đủ thì lập luận "hỏng do bảo dưỡng kém" bị bác. Không có hồ sơ thì công ty gần như luôn
phải chịu vì không chứng minh được điều ngược lại.

Đây là lý do thực dụng để làm module bảo dưỡng nghiêm túc — cùng bản chất với biên bản bàn giao
trong vụ phạt nguội (BR-702): thứ trông như giấy tờ hành chính nhưng thực ra quyết định ai trả tiền.
*Nguồn: Chief Architect tư vấn, Tech Owner duyệt 24/08/2026.*

### BR-427 — Gia hạn ghi thành bản ghi riêng, không sửa đơn gốc ✅ GĐ1
Khi khách gia hạn (BR-423), hệ thống tạo một **bản ghi gia hạn gắn vào đơn**, **không sửa đơn gốc**.

- Khách nhìn thấy **một đơn duy nhất**.
- Kế toán thấy **hai dòng** — tiền thuê gốc và tiền gia hạn.
- Đơn gốc giữ nguyên giá đã đóng băng theo BR-122.

Sửa thẳng vào đơn đã `CONFIRMED` sẽ phá nguyên tắc đóng băng giá và làm mất khả năng đối chiếu
số tiền khách đã đồng ý ban đầu.
*Nguồn: Chief Architect, Tech Owner duyệt 24/08/2026.*

### BR-428 — Sự cố phát hiện sau khi đơn hoàn tất ✅ GĐ1
Hư hỏng chỉ lộ ra sau khi đơn đã `COMPLETED` (ví dụ khi vệ sinh kỹ hôm sau): **không mở lại đơn**.
Tạo một **hồ sơ sự cố độc lập** tham chiếu tới đơn đã hoàn tất, xử lý theo BR-426.

Mở lại trạng thái cuối sẽ làm hỏng báo cáo và tạo đường cho việc sửa dữ liệu cũ. Đây cùng hình dạng
với phạt nguội (BR-704): chi phí hiện ra sau khi đóng đơn, xử lý bằng bằng chứng chứ không bằng cách
quay ngược trạng thái.
*Nguồn: Chief Architect, Tech Owner duyệt 24/08/2026.*

---

## 5xx — Tài xế

### BR-501 — Mỗi chuyến có tài xế phải có đúng một người lái được khai báo ✅ GĐ1
Chuyến thuê có tài xế phải có **đúng một** người lái được khai báo, với **danh tính và giấy phép lái
xe đã xác minh**, và thông tin này **khách thuê nhìn thấy được**.
*Nguồn: §6.2.*

### BR-502 — Không có người lái khai báo thì chuyến không khởi hành ✅ GĐ1
Đây là ràng buộc **cứng**. Không có ngoại lệ, không có "bổ sung sau".
*Nguồn: §6.3.*

### BR-503 — Xác minh giấy phép trước khi khởi hành ✅ GĐ1
Giấy phép lái xe của người lái phải được xác minh **trước** giờ khởi hành, không phải sau.
*Nguồn: §6.3.*

### BR-504 — Bốn cách bố trí người lái ✅
| Loại xe | Ai lái | Ai bố trí | GĐ |
|---|---|---|---|
| Xe công ty | `DRIVER` (nhân viên) | Công ty phân công | 1 |
| Đối tác cá nhân | Chính chủ xe | Chủ xe tự lo | 2 |
| Đối tác doanh nghiệp | Tài xế của họ | Đối tác tự điều phối | 3 |
| — | `FREELANCE_DRIVER` | Tài xế tự nhận | 3 |

Mọi trường hợp đều phải thoả BR-501 → BR-503.
*Nguồn: §6.1.*

### BR-503b — Giấy phép của người lái khai báo phải còn hạn tới hết chuyến ✅ GĐ1
Áp dụng cho cả bốn cách bố trí người lái ở BR-504. Người lái được khai báo phải có GPLX **còn hạn
tới khi chuyến kết thúc**, không chỉ còn hạn lúc phân công.

Với chuyến có tài xế, người chịu trách nhiệm vi phạm là người lái khai báo (BR-703) — nên bằng của
họ mới là bằng có ý nghĩa pháp lý cho chuyến đó.
*Nguồn: Tech Owner chốt 29/08/2026.*

### BR-505 — Đổi tài xế chỉ trước giờ khởi hành ✅ GĐ1
Được phép đổi người lái **trước** giờ khởi hành. Sau khi chuyến bắt đầu, người lái là **dữ liệu bất
biến** của chuyến.
*Nguồn: §6.3.*

### BR-506 — Đổi tài xế phải ghi lịch sử và báo khách ✅ GĐ1
Mọi lần đổi người lái đều ghi lịch sử và **thông báo cho khách**. Khách đặt xe có tài xế là đang tin
vào một người cụ thể.
*Nguồn: §6.3.*

### BR-507 — Một tài xế không nhận hai chuyến chồng giờ ✅ GĐ1
Tại mọi thời điểm, một người lái chỉ được phân công cho **tối đa một** chuyến.
Cùng bản chất tranh chấp tài nguyên như chiếc xe (BR-104).
*Nguồn: §6.3.*

### BR-508 — Tài xế nghỉ đột xuất trước giờ khởi hành ✅ GĐ1
Điều phối **gán tài xế khác và báo khách ngay** (BR-506).

Nếu không có tài xế nào thay được, coi như **công ty huỷ đơn** theo BR-303: cần `BRANCH_MANAGER`
duyệt, khách hoàn **100%**.
*Nguồn: Tech Owner duyệt 24/08/2026.*

---

## 6xx — Bảo hiểm

### BR-601 — Bảo hiểm mua theo chuyến ✅ GĐ1
Nền tảng mua **bảo hiểm theo từng chuyến**, không mua bảo hiểm năm cho từng xe.
*Nguồn: §12.*

### BR-602 — Mức miễn thường của khách ✅ GĐ1
Khi có sự cố ngoài ý muốn, khách thuê chịu **tối đa 2.000.000đ mỗi sự cố**. Phần vượt do bảo hiểm gánh.
*Nguồn: §12, §12.1.*

### BR-603 — Bảo hiểm thân vỏ không bắt buộc với xe đối tác ✅ GĐ2
Xe đối tác **không bắt buộc** có bảo hiểm thân vỏ riêng. Bảo hiểm TNDS thì bắt buộc — nhưng đã nằm
trong yêu cầu giấy tờ hợp lệ theo luật (BR-005).
*Nguồn: §12.2.*

---

## 7xx — Phạt nguội giao thông

### BR-701 — Truy vết người lái theo thời điểm ✅ GĐ1
Hệ thống phải trả lời được câu hỏi **"ai đang lái xe X vào thời điểm T"** với bất kỳ T nào trong quá
khứ, kể cả nhiều tháng sau.

Đây không phải tiện ích báo cáo. Theo Nghị định 336/2025/NĐ-CP, thông báo phạt nguội gửi cho **chủ
xe** — tức là công ty với mọi xe công ty. Công ty chỉ **không bị xử phạt** nếu chứng minh được người
vi phạm là khách thuê.
*Nguồn: §11.1, §11.2.*

### BR-702 — Bộ hồ sơ bằng chứng ✅ GĐ1
Với mỗi đơn thuê, hệ thống lưu bất biến: **hợp đồng thuê xe · ảnh CCCD · ảnh giấy phép lái xe ·
biên bản bàn giao có mốc thời gian và ODO · chữ ký hai bên**.

Thiếu bất kỳ mảnh nào, công ty mất khả năng chuyển trách nhiệm và phải tự chịu tiền phạt.
*Nguồn: §11.2.*

### BR-703 — Ai chịu trách nhiệm vi phạm ✅ GĐ1
- Chuyến **tự lái**: người thuê (BR-107 bảo đảm người thuê chính là người lái).
- Chuyến **có tài xế**: **người lái được khai báo** (BR-501), không phải người thuê.
*Nguồn: §11.2.*

### BR-704 — Không giữ thế chấp chờ phạt nguội ✅ GĐ1
Thế chấp hoàn ngay sau khi trả xe (BR-212). Khi có thông báo phạt, công ty **dùng bộ hồ sơ bằng
chứng làm việc với cơ quan chức năng và với khách**.

**Đánh đổi đã được Tech Owner chấp nhận:** với xe công ty thuê ngày (không có thế chấp) và thế chấp
hoàn ngay, khi giấy phạt về thì không còn khoản tiền nào của khách trong tay công ty. Việc thu lại
phụ thuộc vào bằng chứng và thiện chí khách. Ưu tiên trải nghiệm khách hơn bịt kín rủi ro.
*Nguồn: §11.3, Tech Owner chốt N18 ngày 24/08/2026.*

### BR-705 — Thiết bị giám sát trên xe công ty ✅ GĐ1
Mọi xe công ty gắn **GPS** và **camera hành trình**.
*Nguồn: §11.3.*

### BR-706 — Tra cứu phạt nguội tại thời điểm nhận lại xe ✅ GĐ1
Khi khách trả xe, hệ thống **tra cứu phạt nguội theo biển số ngay tại thời điểm đó**, khi khách còn
có mặt. Vi phạm đã có trên hệ thống thì xử lý tại chỗ.

Việc tra cứu **không được làm chậm việc hoàn thế chấp** (BR-212). Không bắt được vi phạm chưa kịp
cập nhật, nhưng bắt được phần đã có.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-707 — Ghi vi phạm vào hồ sơ khách ✅ GĐ1
Mọi vi phạm giao thông và sự cố hư hỏng do khách gây ra được ghi vào hồ sơ khách. Khách tái phạm
nhiều lần bị hạn chế thuê.
⚠️ Ngưỡng "nhiều lần" và mức hạn chế chưa chốt.
*Nguồn: Tech Owner duyệt 24/08/2026.*

---

## 8xx — Chi nhánh và phân quyền

### BR-801 — Danh sách vai ✅ GĐ1
Tên vai dùng **tiếng Anh**.

| Vai | Mô tả | GĐ |
|---|---|---|
| `CUSTOMER` | Khách thuê | 1 |
| `BRANCH_STAFF` | Nhân viên vận hành chi nhánh | 1 |
| `BRANCH_MANAGER` | Quản lý chi nhánh | 1 |
| `DELIVERY_STAFF` | Nhân viên giao xe | 1 |
| `DRIVER` | Tài xế là nhân viên | 1 |
| `ACCOUNTANT` | Kế toán | 1 |
| `OPERATIONS_MANAGER` | Quản lý vận hành — duyệt điều chuyển xe | 1 |
| `ADMIN` | Toàn quyền | 1 |
| `HOST` | Đối tác cá nhân | 2 |
| `BUSINESS_PARTNER` | Đối tác doanh nghiệp | 3 |
| `BUSINESS_PARTNER_STAFF` | Tài khoản con của đối tác doanh nghiệp | 3 |
| `FREELANCE_DRIVER` | Tài xế tự do | 3 |
*Nguồn: §4, Tech Owner chốt N20 ngày 24/08/2026.*

### BR-802 — Ranh giới dữ liệu theo chi nhánh ✅ GĐ1
Người dùng thuộc một chi nhánh **không được xem hay can thiệp** hợp đồng, đơn thuê của chi nhánh khác.
*Nguồn: §3.*

### BR-803 — Ngoại lệ xuyên chi nhánh ✅ GĐ1
Chỉ `ADMIN` được xem và xử lý dữ liệu xuyên chi nhánh.
*Nguồn: §3.*

### BR-804 — Yêu cầu hỗ trợ giữa chi nhánh ✅ GĐ1
Chi nhánh được gửi **yêu cầu hỗ trợ** tới chi nhánh khác (ví dụ nhờ hỗ trợ bảo dưỡng). Yêu cầu này
là kênh hợp tác chính thức, **không** phải là quyền truy cập dữ liệu — bên nhận vẫn tự thao tác trên
phần của mình.
*Nguồn: §3.*

---

## Quy tắc chưa chốt — KHÔNG hiện thực

### Chưa chốt, không chặn

| Nội dung | Ghi chú |
|---|---|
| Khoảng cách từ chi nhánh tới trạm sạc / cây xăng gần nhất | Chi nhánh không có trạm tại chỗ (BR-420), nên khoảng đệm 2 giờ chỉ đủ khi trạm gần |
| Danh mục giấy tờ bắt buộc khi duyệt xe đối tác (BR-009) | GĐ2 |
| Vai nào thực hiện duyệt KYC đối tác | GĐ2 |
| Khoảng đệm có khác nhau giữa nhận tại chi nhánh và giao tận nơi không | |
| Khoá vận hành (bảo dưỡng, điều chuyển, chủ xe tự khoá) xong sớm hoặc tạo nhầm: có được nhả phần còn lại hoặc huỷ không | Hiện tại hoàn tất vẫn chặn tới hết khoảng đã đặt, và không có cách huỷ — status-flow §2. Chưa ai gọi nên chưa gấp |

---

## Sạc xe điện — rủi ro còn lại

BR-418 quyết toán ngay lúc trả xe nên xử lý được phần lớn. Khoảng hẹp còn lại là khi **phiên sạc chưa
kịp hiện trong app của hãng tại thời điểm khách trả xe**, và chỉ xuất hiện vài ngày sau.

**Tình huống cụ thể:**
1. Khách thuê xe điện của đối tác, trong chuyến sạc 2 lần ở trạm của hãng — tiền tự trừ vào tài khoản chủ xe.
2. Khách trả xe. Chủ xe mở app, chỉ thấy **1 phiên** đã đồng bộ. Quyết toán tại chỗ theo 1 phiên đó.
3. Khách trả tiền phiên 1, ký biên bản, ra về.
4. Hai ngày sau phiên thứ 2 hiện lên: 300.000đ. Chủ xe báo nền tảng.
5. Nền tảng liên hệ khách đòi 300.000đ. Khách **chối hoặc không phản hồi**.

Đến bước 5 thì không còn công cụ nào để thu: thế chấp đã hoàn (BR-212), đơn đã đóng, và số tài khoản
khách lưu trong app (BR-214) chỉ dùng để **chuyển tiền cho khách**, không thể tự trừ tiền của khách.

**Đã chốt — xem BR-422.**

### BR-708 — Mức hạn chế khách vi phạm ✅ GĐ1
Hai mức, dựa trên hồ sơ vi phạm ở BR-707:

| Ngưỡng | Hậu quả |
|---|---|
| **3 lần / 12 tháng** | **Cảnh báo nội bộ** — nhân viên thấy khi xử lý đơn của khách này |
| **5 lần / 12 tháng** | **Chặn đặt xe** |

Việc chặn **cần `ADMIN` duyệt, không tự động**. Chặn nhầm một khách tốt là mất họ vĩnh viễn, và
hệ thống đếm vi phạm không phân biệt được vi phạm nghiêm trọng với lỗi vặt.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-805 — Nhân viên nghỉ việc ✅ GĐ1
Hệ thống **chặn khoá tài khoản nhân viên** khi người đó còn chuyến hoặc đơn đang được gán.
Phải bàn giao hết trước.

Không có ràng buộc này thì sẽ có những chuyến gán cho người không còn ở công ty, và không ai phát
hiện cho tới hôm khách đứng chờ.
*Nguồn: Tech Owner duyệt 24/08/2026.*

### BR-807 — Mỗi chi nhánh có một quản lý ✅ GĐ1
Mỗi chi nhánh có **đúng một** `BRANCH_MANAGER` chịu trách nhiệm.

Vai này là chủ thể của nhiều quyết định trong hệ thống: duyệt xe lên sàn (BR-010), duyệt huỷ đơn
(BR-303), yêu cầu điều chuyển xe (BR-006), xác nhận thanh toán thay (BR-217), và nhận cảnh báo giấy
tờ sắp hết hạn (BR-017). Không có người này thì các luồng đó không có ai đứng tên.
*Nguồn: Tech Owner chốt 23/08/2026 (phân tích §3); tách thành quy tắc riêng 07/09/2026 —
trước đó bị gán nhầm vào BR-003.*

### BR-808 — Chi nhánh có tên và địa chỉ hiển thị cho khách ✅ GĐ1
Mỗi chi nhánh có **tên** và **địa chỉ** — bắt buộc, hiển thị trong kết quả tìm kiếm và khi đặt xe.
Toạ độ (BR-003) cho hệ thống biết khoảng cách; tên và địa chỉ cho khách biết phải tới đâu nhận xe.
Kết quả tìm kiếm chỉ ghi "cách 3,2 km" thì khách không biết đó là ở đâu.
*Nguồn: Tech Owner duyệt 04/10/2026.*

### BR-806 — Đóng chi nhánh ✅ GĐ1
Chặn đóng chi nhánh khi còn xe hoặc còn đơn hiệu lực. Phải điều chuyển xe đi (BR-006) và xử lý đơn
trước. Cùng khuôn với BR-014.
*Nguồn: Tech Owner duyệt 24/08/2026.*

---

## 9xx — Đánh giá

### BR-901 — Khách đánh giá chuyến đi ✅ GĐ1
Sau khi đơn hoàn tất, khách được đánh giá chuyến đi (xe và dịch vụ). Đánh giá **hiển thị công khai**
trên trang chi tiết xe.

Cần tích luỹ đánh giá **trước** khi có đối tác: xe không có đánh giá nào thì khách mới không dám đặt.
Đây cũng là cách sớm nhất để biết chi nhánh nào đang làm tệ.
*Nguồn: Tech Owner chốt 24/08/2026.*

### BR-902 — Đánh giá gắn với đơn thuê có thật ✅ GĐ1
Chỉ khách **đã hoàn tất một đơn thuê** mới đánh giá được, và mỗi đơn đánh giá **đúng một lần**.
Không cho đánh giá tự do.

Đánh giá không gắn đơn thật là cửa ngõ cho đánh giá giả — vừa thổi phồng xe của mình, vừa dìm đối
thủ khi có đối tác ở GĐ2.
*Nguồn: nguyên tắc chống gian lận, hệ quả của BR-901.*

### BR-903 — Chi tiết đánh giá ✅ GĐ1
- Thang **5 sao**, kèm **bình luận tuỳ chọn**.
- Khách có **14 ngày** sau khi kết thúc chuyến để đánh giá. Quá hạn thì đóng.
- Công ty (và chủ xe ở GĐ2) được **phản hồi công khai một lần** cho mỗi đánh giá.

Cho phản hồi một lần là có chủ đích: đủ để nêu phía mình khi bị đánh giá oan, không đủ để biến trang
xe thành nơi cãi nhau qua lại.
*Nguồn: Tech Owner duyệt 24/08/2026.*

---

## 10xx — Thông báo

### BR-1001 — Hai loại thông báo, quyền tắt khác nhau ✅ GĐ1
| Loại | Nội dung | Khách tắt được không |
|---|---|---|
| **Giao dịch** | Đơn, tiền, thời gian, người lái, sự cố | **Không** |
| **Tiếp thị** | Khuyến mãi, gợi ý xe, tin tức | **Có**, và phải dễ tắt |

Gộp hai loại vào một công tắc là cách nhanh nhất để khách tắt hết — rồi họ lỡ hạn thanh toán giữ chỗ
và đổ lỗi cho hệ thống.
*Nguồn: Chief Architect, Tech Owner yêu cầu soạn bộ quy tắc thông báo 24/08/2026.*

### BR-1002 — Thông báo nhạy thời gian phải qua kênh tức thời ✅ GĐ1
Những thông báo có hạn hành động (giữ chỗ 1 tiếng, sắp tới giờ nhận xe, tài xế đổi) **bắt buộc** gửi
qua **push hoặc SMS**, không chỉ email.

Với thời hạn giữ chỗ 1 tiếng (BR-103) và giới hạn đặt trước tối thiểu 1 tiếng (BR-121), mọi thứ trong
hệ thống này diễn ra rất gấp. Email không kịp.
*Nguồn: Chief Architect.*

### BR-1003 — Mỗi thông báo phải nói rõ cần làm gì ✅ GĐ1
Thông báo phải nêu: chuyện gì xảy ra, ảnh hưởng thế nào, và **hành động tiếp theo là gì** kèm đường
dẫn tới đúng chỗ.

"Đơn của bạn đã cập nhật" là thông báo vô dụng — nó buộc khách mở app để tìm hiểu, và nếu việc gấp
thì khách đã mất thời gian.
*Nguồn: Chief Architect.*

### BR-1004 — Thông báo bắt buộc cho khách ✅ GĐ1

| Sự kiện | Thời điểm | Kênh |
|---|---|---|
| Đặt xe thành công, cần trả cọc | Ngay | Push/SMS |
| **Sắp hết hạn giữ chỗ** | Còn 15 phút | **Push + SMS** |
| Hết hạn giữ chỗ, đơn đã huỷ | Ngay | Push/SMS |
| Đã nhận cọc, đơn được xác nhận | Ngay | Push |
| Nhắc trước giờ nhận xe | Trước 2 giờ (gói ngày/tháng) · trước 30 phút (gói giờ) | Push |
| Đổi người lái (BR-506) | Ngay | **Push + SMS** |
| Nhân viên đang trên đường giao xe | Khi xuất phát | Push |
| Nhắc trước giờ trả xe | Trước 1 giờ | Push |
| Quyết toán khi trả xe (năng lượng, phụ phí) | Ngay tại chỗ | In-app + email |
| Hoàn tiền đã chuyển | Khi chuyển | Push + email |
| Nhắc đánh giá | 2 giờ sau khi trả xe | Push |
| **Đơn bị huỷ bởi công ty hoặc đối tác** | Ngay | **Push + SMS + gọi điện** |
| Phát hiện phạt nguội liên quan chuyến của mình | Khi phát hiện | Push + email |

Riêng đơn bị huỷ bởi phía cung: **phải có cuộc gọi người thật**, không chỉ thông báo tự động.
Đây là tình huống tổn hại niềm tin nặng nhất, và cũng là lúc nhân viên có cơ hội cứu đơn bằng
nghiệp vụ đổi xe (BR-305).
*Nguồn: Chief Architect.*

### BR-1005 — Thông báo cho hợp đồng dài hạn ✅ GĐ1
| Sự kiện | Thời điểm |
|---|---|
| Kỳ thanh toán sắp đến hạn | Trước 3 ngày (khớp BR-221) |
| Đúng ngày đến hạn | Ngày đến hạn |
| Trễ kỳ, đang trong ân hạn | Ngày trễ thứ 1 và thứ 3 |
| Bắt đầu tính phí trễ hạn | Ngày trễ thứ 4 |
| Cảnh báo sắp đình chỉ hợp đồng | Ngày trễ thứ 6 |
| Đến hạn bảo dưỡng, cần đưa xe đi (BR-013) | Trước 7 ngày |
*Nguồn: Chief Architect.*

### BR-1006 — Thông báo cho nhân sự nội bộ ✅ GĐ1
| Vai | Sự kiện |
|---|---|
| `BRANCH_STAFF` | Đơn mới cần chuẩn bị xe · khách sắp đến nhận · xe sắp đến hạn trả |
| `DELIVERY_STAFF` | Được phân công lượt giao xe |
| `DRIVER` | Được phân công chuyến · chuyến bị đổi hoặc huỷ |
| `BRANCH_MANAGER` | Xe sắp đến hạn bảo dưỡng/đăng kiểm · đơn cần duyệt huỷ · hợp đồng có kỳ quá hạn |
| `OPERATIONS_MANAGER` | Yêu cầu điều chuyển xe cần duyệt |
| `ACCOUNTANT` | Cần xác nhận thanh toán thủ công · cần xuất hoá đơn · cần xử lý hoàn tiền |
*Nguồn: Chief Architect.*

### BR-1007 — Thông báo cho đối tác ✅ GĐ2
| Sự kiện | Thời điểm | Kênh |
|---|---|---|
| Có yêu cầu thuê xe của mình | Ngay | **Push + SMS** |
| Khách sắp đến nhận xe | Trước 2 giờ | Push |
| Tiền đã được chi trả | Khi chuyển | Push + email |
| Đánh giá mới về xe của mình | Ngay | Push |

Thời gian phản hồi của chủ xe là chỉ số sống còn của marketplace — thông báo yêu cầu thuê phải là
loại khó bỏ lỡ nhất trong toàn hệ thống.
*Nguồn: Chief Architect.*

### BR-1008 — Không gửi trùng ✅ GĐ1
Một sự kiện chỉ gửi **một lần trên một kênh**. Gửi đồng thời nhiều kênh chỉ dành cho các sự kiện đã
đánh dấu **Push + SMS** ở BR-1004, BR-1007.

Gửi lại chỉ được phép khi hành động vẫn chưa được thực hiện và thời hạn đang cạn.
*Nguồn: Chief Architect.*
