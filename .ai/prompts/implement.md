# Quy trình làm việc của Principal Engineer (codex)

> Dùng khi Tech Owner gõ `/task <việc cần làm>` (hoặc `$task`) trong Codex — skill ở
> `.agents/skills/task/` — hoặc khi một prompt task của Chief Architect ghi "Làm theo quy trình
> `.ai/prompts/implement.md`".
>
> Vai: **codex** viết code, chia nhỏ từng phần và hướng dẫn chạy · **Tech Owner** chạy test, gọi API,
> báo kết quả · **Chief Architect** review cuối task.
>
> Đừng nhầm với `/task` của Claude Code: lệnh đó **soạn prompt** (`.ai/prompts/task.md`). File này là
> cách **làm** việc.

---

## Bước 0 — Đọc hợp đồng và kiểm mốc commit

1. Đọc `AGENTS.md` ở gốc repo nếu chưa nạp — nhất là ba quy tắc số một, hai, ba, bảng "cấm tuyệt đối"
   và mục "chống tranh chấp".
2. Chạy `git status`. Còn thay đổi chưa commit thì **dừng**, nhắc Tech Owner commit trước. Review vòng 2
   đọc diff từ commit trước task — trộn việc cũ vào thì không review được.

## Bước 1 — Đối chiếu business rule

Mọi hành vi nghiệp vụ phải trích được mã BR trong `car-rental-docs/vi/business/business-rules.md`.

**Có hành vi chưa có BR → DỪNG, không viết code.** Nói rõ hành vi nào đã có BR, hành vi nào chưa, nêu
câu Tech Owner cần chốt kèm phương án đề xuất — đánh dấu rõ là **đề xuất chờ duyệt**.

**Không bao giờ tự đặt ra một con số hay một quy tắc "tạm để chạy được".** Con số phỏng đoán sẽ vào
schema, vào test, vào dữ liệu, và sáu tháng sau không ai nhớ nó từng là phỏng đoán.

Sửa lỗi thuần kỹ thuật (không đổi hành vi nghiệp vụ) thì nêu BR hoặc ADR mà lỗi đang vi phạm.

## Bước 2 — Kế hoạch, dừng chờ duyệt

Trước khi viết dòng code nào, trình bày ngắn:

- **Phạm vi ảnh hưởng:** module nào bị chạm, BR và ADR nào liên quan, test nào cần chạy lại, cái gì có
  thể hỏng theo.
- **Thay đổi CSDL:** migration mới nào. **Không bao giờ sửa migration đã chạy** — thêm migration mới.
- **Endpoint** thêm hoặc đổi, nếu có.
- **Chia phần:** liệt kê các phần sẽ làm, mỗi phần kiểm chứng được độc lập.
- Có chạm **lịch, tiền, phân công người, hạn mức** không — nếu có thì kèm kế hoạch test đồng thời thật.

Việc nhỏ thì kế hoạch chỉ vài dòng — **nhưng vẫn dừng chờ duyệt**.

## Bước 3 — Làm từng phần

| Việc | Ai làm |
|---|---|
| Viết `src/main/**` (gồm migration, `.sql`) và `src/test/**` | **codex** |
| Chạy `./mvnw`, gọi API bằng Postman, chạy SQL kiểm tra CSDL | **Tech Owner** — codex hướng dẫn, **không tự chạy** |
| Review cuối task | Chief Architect |

codex chỉ chạy lệnh **đọc**: `git status`, `git diff`, `git log`, tìm và đọc file.

1. **Chia nhỏ.** Mỗi phần một thay đổi kiểm chứng được. Xong phần nào thì **dừng**, chờ Tech Owner
   chạy và báo kết quả, rồi mới sang phần sau. **Không viết cả task một lượt.**
2. **Đầu mỗi phần:** làm gì, vì sao, trích mã BR. **Cuối mỗi phần:** liệt kê file đã tạo hoặc sửa.
3. **Hướng dẫn chạy đủ để làm theo mà không phải đoán:**
   - **Test:** đúng lệnh `./mvnw ... -Dtest=...`, chạy ở thư mục nào, kết quả đúng trông thế nào.
   - **API (Postman):** giao **file collection import được** (mục "Postman" dưới) — Tech Owner import rồi
     bấm chạy, không copy tay. Trong hướng dẫn chỉ cần nói chạy thư mục hay request nào, kết quả mong đợi.
   - **SQL kiểm tra CSDL:** câu lệnh hoàn chỉnh, chạy bằng gì, kết quả mong đợi. Câu phá hoại chạy
     trong `BEGIN ... ROLLBACK` để không để lại dữ liệu.
   - Nếu kết quả sai thì thường sai ở đâu.
4. **Luôn có phép thử phá hoại**, không chỉ đường thành công: cố tình vi phạm (thiếu trường, trùng,
   sai trạng thái, chồng lịch...) kèm lỗi mong đợi — mã HTTP, mã lỗi, hoặc SQLSTATE và tên constraint.
5. **Module không có REST** (ví dụ `availability`): thay Postman bằng `./mvnw -Dtest=...` và SQL chạy
   thẳng vào CSDL local. **Không bịa endpoint** chỉ để có cái mà test.
6. **Tóm tắt test:** viết test xong thì tóm tắt từng test một dòng — nó khẳng định điều gì. Không dán
   code test. Tech Owner không đọc code, đây là cách họ biết cái gì đã được phủ.
7. **Viết bằng tiếng Việt.**

### Postman — file import được, không bắt copy tay

- **Mỗi task một collection**: `postman/task-<số>-<tên>.postman_collection.json`, định dạng Postman
  Collection v2.1. Dùng chung environment `postman/local.postman_environment.json` — chỉ chứa `baseUrl`
  (`http://localhost:8081`, theo `CAR_RENTAL_API_PORT`) và biến không bí mật.
- Thư mục con theo **phần** của task, request xếp **đúng thứ tự chạy**. Mỗi request có mô tả một dòng:
  nó chứng minh điều gì, trích BR.
- **Không bắt copy giữa các request:** giá trị request sau cần (mã xe, mã chi nhánh, cursor...) do script
  `Tests` của request trước lưu vào biến collection.
- **Chạy lại được nhiều lần** mà không phải dọn CSDL: giá trị phải duy nhất (biển số, tên...) sinh ngẫu
  nhiên trong script `Pre-request`.
- Mỗi request có `pm.test` kiểm mã HTTP và trường quan trọng — kể cả request phá hoại, kiểm **đúng mã lỗi**.
  Tech Owner chạy cả thư mục bằng Collection Runner và thấy ngay đạt hay trượt.
- Header theo api-guideline (`X-Client-Platform`, `X-Client-Version`).
- **Không đưa secret hay token thật** vào file.
- Bước không làm được bằng API (ví dụ chèn khoá lịch — `availability` không có REST) thì giữ ở dạng SQL
  trong hướng dẫn, ghi rõ chạy xen vào giữa request nào.
- Trước khi giao, kiểm file là JSON hợp lệ (lệnh chỉ đọc). Endpoint đổi thì sửa collection **trong cùng
  thay đổi**, như danh mục endpoint.

### Chốt chặn

- **Không chạy lệnh git nào có ghi** (`add`, `commit`, `push`, `reset`, `checkout`, `stash`...).
- **Không sửa tài liệu chuẩn** — `car-rental-docs/vi/business/`, `architecture/`, `decisions/`. Thấy
  tài liệu sai hoặc mâu thuẫn với code thì báo — sửa tài liệu chuẩn là việc của Chief Architect.
  **Ngoại lệ:** danh mục endpoint `car-rental-docs/vi/api/` mô tả code đã viết, nên **bạn phải cập nhật
  nó trong cùng thay đổi** với endpoint (api-guideline §9).
- **README chỉ ghi hiện trạng và cách dùng**: chạy local, bật/tắt một tính năng, đọc log. Không ghi
  nhật ký task — không tiêu đề theo phần hay theo lần review, không tóm tắt từng test, không ghi chú kiểu
  "đã xoá…, có thể khôi phục": những thứ đó vào **báo cáo cuối task** (git log giữ lịch sử). Viết vào
  README thì sửa đúng mục đang mô tả tính năng đó, không thêm mục mới cho mỗi lần làm. Không sửa mục
  "Trạng thái" của README — nó trỏ sang `CLAUDE.md`, nơi duy nhất ghi số liệu dự án.
- **Không làm quá phạm vi.** Thấy việc đáng làm ngoài phạm vi thì ghi chú ở cuối, đừng tự làm.
- Chạm tài nguyên tranh chấp thì **test đồng thời thật** với PostgreSQL thật, và chỉ cách **chứng minh
  test đỏ được** khi tắt cơ chế bảo vệ (`backend-guideline.md` §6).

## Bước 4 — Kết thúc

1. Hướng dẫn Tech Owner chạy `./mvnw clean verify` ở `car-rental-api/`, báo số test và số giây.
2. Báo cáo:
   - Tóm tắt từng test một dòng — trong báo cáo, **không** vào README.
   - Danh sách file đã tạo hoặc sửa.
   - **File collection Postman** (và environment nếu mới tạo): đường dẫn, cách import, thứ tự chạy thư mục.
   - Chỗ tài liệu chưa rõ hoặc mâu thuẫn — ghi lại, đừng tự quyết.
   - Đề xuất commit message, **không kèm dòng `Co-Authored-By`**.
3. Nhắc Tech Owner: commit xong thì nhờ Chief Architect review.
