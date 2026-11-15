# Quy trình soạn prompt task cho codex

> Dùng bởi `/task` của Claude Code (`.claude/commands/task.md` chỉ trỏ tới file này).
>
> Vai: **Chief Architect** soạn prompt và review cuối · **codex** (Principal Engineer) viết code,
> chia nhỏ từng phần và hướng dẫn chạy · **Tech Owner** chạy test, gọi API, báo kết quả.
>
> Từ 23/10/2026 Chief Architect **chỉ đọc code khi review**, không chạy build, test hay SQL — số đo lấy
> từ báo cáo cuối task của codex (`implement.md`, Bước 4, mục "Số đo").
>
> Từ Task 5b (02/10/2026). Trước đó Tech Owner tự gõ code `main`.
>
> Đừng nhầm với `/task` của Codex: lệnh đó **làm** việc (`.ai/prompts/implement.md`). File này là cách
> **soạn prompt**.

---

## Bước 1 — BẮT BUỘC làm trước: đối chiếu business rules

Đọc `car-rental-docs/vi/business/business-rules.md` và xác định **từng hành vi nghiệp vụ** trong
yêu cầu trích được mã BR nào.

**Nếu mọi hành vi đều có BR** → sang bước 2.

**Nếu có hành vi chưa có BR** → **DỪNG. Không soạn prompt.** Thay vào đó:

- Liệt kê rõ: hành vi nào đã có BR, hành vi nào chưa.
- Với phần chưa có, nêu **những câu Tech Owner cần chốt**, kèm phương án đề xuất và **đánh dấu rõ
  là đề xuất chờ duyệt**.
- Chỉ ra những BR hiện có mà tính năng mới có thể **mâu thuẫn** — đây là phần dễ bỏ sót nhất.
- Nói rõ: chốt xong mới soạn được prompt.

**Không bao giờ tự đặt ra một con số hay một quy tắc "tạm để chạy được".** Con số phỏng đoán sẽ vào
schema, vào test, vào dữ liệu, và sáu tháng sau không ai nhớ nó từng là phỏng đoán.

## Bước 2 — soạn prompt theo khung chuẩn

Xuất ra **một khối markdown liền** để Tech Owner copy nguyên khối đưa cho codex.

### §1 Bối cảnh
Repo ở `/Users/diepchu/project/car-rental/`. Nêu trạng thái hiện tại của code, và **liệt kê cụ thể
những tài liệu codex phải đọc trước** — chỉ những cái liên quan tới task này, không liệt kê hết.

Nêu phân vai: codex viết code · Tech Owner chạy test và gọi API theo hướng dẫn của codex ·
Chief Architect đã viết tài liệu, sẽ review sau cùng.

### §2 Quy trình làm việc của codex
Dòng đầu tiên của §2 trong prompt, chép nguyên văn:

> **Làm theo quy trình `.ai/prompts/implement.md`** — chia việc, cách hướng dẫn chạy test và Postman,
> chốt chặn đều ở đó.

**Không chép lại các quy tắc đó vào prompt.** Chép lại thì hai bản sẽ lệch nhau, và codex không biết
tin bản nào. Tech Owner cũng có thể gõ thẳng `$task <việc>` trong Codex cho việc nhỏ — cùng một quy trình.

Chỉ ghi thêm những điều **riêng của task này**, ví dụ:
- Module không có REST → ghi rõ, để codex không bịa endpoint chỉ để có cái mà test.
- Tiêu chí nào phải chứng minh test **đỏ được** khi tắt cơ chế bảo vệ.
- Gợi ý chia phần khi thứ tự quan trọng (migration trước, code dùng nó sau).

**Tech Owner không đọc code** — cả `main` lẫn test. Họ chỉ thấy hành vi qua kết quả chạy. Nên tiêu chí
nghiệm thu ở §6 phải là thứ họ **tự chạy được và tự thấy được**.

### §3 Bước kế hoạch — điều kế hoạch phải trả lời
`implement.md` luôn bắt codex trình bày kế hoạch và chờ duyệt. Với task lớn hoặc tinh tế, prompt nêu thêm
**những câu kế hoạch phải trả lời được**.

Bài học thật: ở Task 3 và Task 4, bước này khiến codex tìm ra **lỗi trong chính tài liệu kiến trúc**
trước khi một dòng code nào được viết. Nếu bỏ qua, những lỗi đó đã nằm trong code.

Nêu rõ cần trình bày gì: lựa chọn công cụ và lý do · cơ chế cho từng phần khó · những thứ tham chiếu
tới cái chưa tồn tại thì xử lý sao.

### §4 Việc cần làm
**Trích mã BR cho từng hành vi.** Không trích được thì quay lại bước 1.

### §5 Ràng buộc
- Cấu trúc thư mục theo `development-guideline.md` §5.
- Chuẩn code theo `module-architecture.md`.
- Không commit secret.
- **Không chạy lệnh git nào có ghi** (`add`, `commit`, `push`, `reset`, `checkout`...).
  Được phép **đề xuất** commit message.

### §6 Tiêu chí nghiệm thu
Phải là thứ Tech Owner **tự chạy lệnh hoặc tự gửi request kiểm được**, không phải "codex bảo xong".

Ba loại tiêu chí đã chứng minh giá trị:
- **Phép thử phá hoại** — ràng buộc CSDL thì phải *cố tình vi phạm* rồi xem có bị chặn không.
  Test "tạo thành công" không chứng minh gì về bất biến.
- **Phép thử đảo** — rule mới thì phải sửa fixture cho hết vi phạm và xem test có **đỏ** không.
  Không có bước này thì rất dễ có một rule rỗng vẫn xanh.
- **Trường hợp được phép** — rule nào có ngoại lệ thì phải có fixture chứng minh ngoại lệ hoạt động.
  Bài học Task 3: R6 có ngoại lệ `*PolicyResolver` nhưng không fixture nào chạm tới nó, nên nhánh
  đó chưa bao giờ được chạy.

### §7 Ngoài phạm vi
Liệt kê **cụ thể** những gì đừng làm, kèm lý do ngắn.

Đây là chỗ hay hỏng nhất: giao việc "dựng X" thì codex sẽ tiện tay dựng luôn Y và Z, và một task
phình thành ba.

### §8 Báo cáo cuối task
- **Số đo — bắt buộc**, theo `implement.md` Bước 4: lệnh đã chạy và ai chạy · số test của **từng dự án bị
  chạm** so với mốc trước task · thời gian build · kết quả phép thử phá hoại · kết quả phép thử đảo (đỏ rồi
  xanh) · số đo hiệu năng nếu task có khẳng định hiệu năng. Thiếu mục này thì Chief Architect chưa review.
- Những lựa chọn kỹ thuật đã chọn và **vì sao**.
- Chỗ nào thấy tài liệu chưa rõ hoặc mâu thuẫn — **ghi lại, đừng tự quyết**.
- **File collection Postman** của cả task trong `postman/` — import được, chạy được bằng Collection Runner
  (`implement.md`, mục "Postman").
- Danh sách file đã tạo hoặc sửa trong cả task.
- Đề xuất commit message (Tech Owner tự chạy lệnh).

## Bước 3 — Gợi ý model cho Tech Owner (ngoài khối prompt)

Sau khối prompt, ghi **một dòng**: chọn model nào, mức suy luận nào trong Codex, và **vì sao — gắn với
nội dung task này**. Không ghi vào trong khối prompt — đó là lựa chọn của Tech Owner, không phải việc
của codex.

Danh sách model đổi theo thời gian (có model bị gỡ). Trước khi gợi ý, đọc
`~/.codex/models_cache.json` để chắc model còn trong danh sách. Không tự đánh giá model nào giỏi hơn
khi không có căn cứ — dựa vào mô tả của Codex và vào kết quả review vòng 2.

Tiêu chí (Tech Owner duyệt 11/10/2026):

| Loại task | Model · mức suy luận |
|---|---|
| Chạm tài nguyên tranh chấp hoặc tiền — giữ chỗ, xác nhận đơn, đổi xe giữ cọc, thanh toán, hạn mức mã giảm giá, phân công người | Mạnh nhất (hiện là **Astra**) · `high` |
| Task thường — module mới không tranh chấp, đọc nhiều, tính toán thuần | Model chính (hiện là **Sol** mới nhất) · `high` |
| Việc nhỏ Tech Owner gõ bằng `$task` — sửa lỗi, đổi tên, cập nhật collection | Model chính · `medium` |

Không dùng mức `low` cho prompt task: prompt dài, nhiều ràng buộc đan nhau (BR, R1–R12, ADR), mức thấp
dễ bỏ sót đúng những thứ đó.

**Prompt sửa lỗi sau review:** chạm đúng vùng tranh chấp hoặc tiền thì giữ model của task gốc; sửa nhỏ
(Postman, tài liệu API, đổi tên) dùng model chính · `medium`.

### Bảng áp dụng cho các task còn lại (Tech Owner duyệt 15/11/2026)

Bảng là **mặc định**, không phải chốt cứng: mỗi lần soạn prompt vẫn đọc lại danh sách model và ghi dòng gợi
ý riêng. Review cho thấy model chính để lọt lỗi ranh giới hay quy tắc ở loại task nào thì đề xuất nâng loại đó.

| Task | Model · mức | Vì sao |
|---|---|---|
| `payment` — trả cọc, xác nhận thủ công | Astra · `high` | Tiền, `Idempotency-Key` |
| `customer-web` | Sol · `high` | Giao diện, không tranh chấp; task frontend đầu tiên — review soi kỹ |
| Nền frontend — springdoc, sinh client, pnpm workspace | Sol · `high` | Hạ tầng, không tranh chấp |
| `admin-web` tối thiểu — hàng chờ giấy tờ, hàng chờ cọc | Sol · `high` | Giao diện; nút xác nhận cọc dựa vào chống trùng ở backend (BR-213) |
| Lát cắt 2 — bàn giao, trả xe, quyết toán | Astra · `high` | Ba khoản tiền, bằng chứng bất biến, đóng băng giá trị quyết toán |
| Lát cắt 3 — huỷ, đổi xe giữ cọc | Astra · `high` | Hoàn tiền; giữ chỗ mới trước khi nhả chỗ cũ |
| Lát cắt 4 — thuê có tài xế | Astra · `high` | Chống trùng lịch tài xế |
| Lát cắt 5 — thuê dài hạn | Astra · `high` | Kỳ thanh toán, phí trễ hạn, đình chỉ |
| Lát cắt 6 — bảo dưỡng, đăng kiểm, điều chuyển | Astra · `high` | Ghi vào bảng khoá lịch |
| Lát cắt 6 — sự cố, phạt nguội | Astra · `high` | Thu tiền của khách (miễn thường, tiền phạt) |
| Lát cắt 6 — đánh giá | Sol · `high` | Không tranh chấp, không tiền |
| `config` (trước khi chạy thật) | Sol · `high` | Không tranh chấp; review soi kỹ việc đọc tham số theo mốc thời gian |
| Thông báo — `notification`, outbox | Astra · `high` | Chống gửi trùng (BR-1008), xử lý đồng thời |
| Giao xe tận nơi | Astra · `high` | Chống trùng lịch nhân viên giao xe |
| Xoay vòng khoá mã hoá | Astra · `high` | Mã hoá, dữ liệu nhạy cảm |
| Mobile | Sol · `high` | Giao diện |
| Việc dọn nhỏ — job dọn OTP, cấu hình IP sau proxy… | Sol · `medium` | Phạm vi hẹp |

Model chưa có căn cứ (ví dụ `gpt-6.1-terra`, xuất hiện 15/11/2026, Codex không mô tả năng lực) không đưa
vào bảng. Muốn thử thì dùng cho một việc nhỏ `$task` trước, rồi xem kết quả review.
