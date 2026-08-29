# Contract cho AI — dự án car-rental

## Dự án

Nền tảng cho thuê xe hơi tại Việt Nam. Mô hình **lai**: marketplace P2P (giống Mioto) **cộng** đội xe
của chính công ty. Startup, chưa có xe, chưa có khách — xây phần mềm trước, chỉ chạy **local**.

**Vai trò:** Tech Owner chốt nghiệp vụ và kiến trúc · Chief Architect (Claude) thiết kế và viết
tài liệu · Principal Engineer (codex) hiện thực theo tài liệu đã chốt.

## Trạng thái

| Hạng mục | Trạng thái |
|---|---|
| Nghiệp vụ giai đoạn 1 | ✅ **155 quy tắc BR đã chốt** |
| Kiến trúc | ✅ 15 ADR ACCEPTED, 10 guideline, 11 rule khoá bằng test |
| Code | 🟡 Đang làm — `shared`, `branch`, `vehicle` xong; 397 test xanh |
| Môi trường | Local, chưa build production |

## Quy tắc số một

**Mọi hành vi nghiệp vụ phải trích được mã BR.**

Không trích được thì **dừng lại và hỏi Tech Owner** — không tự suy diễn, không đoán một con số
"tạm để chạy được". Danh sách những gì **chưa** được chốt nằm ở cuối `business-rules.md`.

## Quy tắc số hai

**Không AI nào được ghi vào git.** Chỉ Tech Owner làm việc đó.

| Được | Không được |
|---|---|
| `git status` · `git log` · `git diff` · `git show` | `git add` · `git commit` · `git push` · `git reset` · `git checkout` · `git stash` · `git rebase` · `git merge` |

Được phép **đề xuất** commit message hoặc nói rõ nên gom những file nào vào một commit.
Tech Owner tự chạy lệnh.

## Quy tắc số ba — cách làm việc

**Đọc code thật trước khi kết luận.** Không suy hành vi từ tên class, tên method hay tên file.
Phân tích một tính năng thì trace đủ đường: **điểm vào → use case → domain → port → adapter → SQL →
CSDL**. Đọc thiếu một tầng thì kết luận về tầng đó là phỏng đoán.

Điều này áp dụng cho cả số liệu: trước khi khẳng định một vấn đề hiệu năng, **chạy và đo**. Đã có
lần suýt báo động vì thấy build dựng 7 container — đo ra thì 397 test hết 70 giây, không phải vấn đề.

**Chỉ phân tích khi được yêu cầu phân tích.** Tech Owner hỏi "chỗ này thế nào" thì trả lời, **không
tự sửa file**. Muốn sửa thì đề xuất trước và chờ đồng ý.

**Sửa code thì phải nêu phạm vi ảnh hưởng.** Module nào bị chạm, quy tắc BR hay ADR nào liên quan,
test nào cần chạy lại, và cái gì có thể hỏng theo.

## Đọc gì trước khi code

Đọc file này trước. Sau đó chỉ đọc phần liên quan tới việc đang làm.

**Nghiệp vụ** — `car-rental-docs/vi/business/`
| File | Khi nào cần |
|---|---|
| `business-rules.md` | Luôn luôn — 149 quy tắc, và mục "chưa chốt" ở cuối |
| `glossary.md` | Trước khi đặt tên bất cứ thứ gì |
| `domain-map.md` | Khi cần biết việc này thuộc vùng nào |
| `status-flow.md` | Khi chạm tới trạng thái đơn, khoá lịch, tiền, hợp đồng |

**Kiến trúc** — `car-rental-docs/vi/architecture/`
| File | Khi nào cần |
|---|---|
| `module-architecture.md` | **Trước dòng code đầu tiên** — chuẩn bắt buộc |
| `module-map.md` | Khi tạo module mới hoặc phân vân ranh giới |
| `backend-guideline.md` | Transaction, mã lỗi, test đồng thời |
| `database-guideline.md` | Migration, ràng buộc, DDL bảng khoá lịch |
| `api-guideline.md` | Thiết kế endpoint, versioning cho mobile |
| `security-guideline.md` | Mọi endpoint có dữ liệu thuộc về một người cụ thể |
| `development-guideline.md` | Quy trình và định nghĩa Done |

**ADR** — `car-rental-docs/vi/decisions/`. Bốn cái quan trọng nhất:
- **ADR-0005** ràng buộc loại trừ chống trùng lịch — quan trọng nhất hệ thống
- **ADR-0004** Hexagonal và phân giải policy cho hai trục
- **ADR-0014** đơn đóng băng mọi giá trị tại thời điểm tạo
- **ADR-0015** hồ sơ bằng chứng bất biến

## Baseline kỹ thuật

Java · Spring Boot · Modular Monolith · Clean/Hexagonal ·
PostgreSQL + PostGIS + btree_gist · **Native SQL, không JPA** · Flyway · schema riêng mỗi module ·
Keycloak (OIDC, có Authenticator OTP qua SMS tự viết) · React + TypeScript (`customer-web`,
`admin-web`) · React Native (mobile) · MinIO · docker-compose.

Cách xây: **lát cắt dọc từng luồng** (ADR-0012), không xây hết backend rồi mới tới frontend.

## Ràng buộc kiến trúc — cấm tuyệt đối

| Cấm | Vì sao |
|---|---|
| Đọc-rồi-ghi để quyết định "xe còn trống" | Luôn hỏng dưới đồng thời — ADR-0005 |
| Module ngoài `availability` ghi vào bảng khoá lịch | Phá bảo đảm chống trùng lịch |
| `if` trên `ownership_type` hoặc `rental_type` ngoài `*PolicyResolver` | Rải rác thì không ai dám sửa |
| Code quyết toán đọc `config` thay vì bản sao trong đơn | Âm thầm tính lại đơn cũ |
| `search` biết xe thuộc ai | BR-112 |
| Xoá cứng dữ liệu trong `handover` và `compliance` | Bằng chứng pháp lý |
| JPA repository cho truy vấn nghiệp vụ | ADR-0003 |
| Quy tắc nghiệp vụ trong controller · trả row CSDL làm response | — |
| Sửa migration đã chạy | — |
| Dùng H2 trong test | Không có exclusion constraint, PostGIS, `tstzrange` |

Những điều trên được khoá bằng `ArchitectureRulesTest` (R1 → R11) và phải xanh trước khi commit.

## Chống tranh chấp — bắt buộc

Đây là hệ thống phân bổ **tài nguyên vật lý không nhân bản được**. Luôn bảo vệ:

- **Trùng lịch xe** — bằng `EXCLUDE USING gist` trên `tstzrange`, **không** bằng kiểm tra ứng dụng,
  **không** bằng khoá Redis.
- **Trùng lịch tài xế và nhân viên giao xe** — cùng cơ chế.
- **Khoá lịch bảo dưỡng, đăng kiểm, điều chuyển** — ghi vào **cùng bảng** với đơn thuê, nếu không
  ràng buộc sẽ không chặn được việc đặt thuê đè lên ngày xe nằm gara.
- **Mọi thao tác ghi tiền** — idempotency key, kể cả thao tác do người thực hiện.
- **Trừ hạn mức mã giảm giá** — cập nhật nguyên tử.
- **Đổi xe giữ cọc** — giữ được chỗ mới **trước khi** nhả chỗ cũ.

Mọi tính năng chạm bốn thứ này — lịch, tiền, phân công người, hạn mức — phải có **test đồng thời
thật** với PostgreSQL thật. Test tuần tự không chứng minh gì.

## Khi hiện thực một tính năng

1. Nêu quy tắc đang hiện thực, **trích mã BR**. Không trích được thì dừng và hỏi.
2. Xác định module bị ảnh hưởng, kiểm tra không vi phạm ranh giới (`module-map.md` §3).
3. Đề xuất thay đổi CSDL và migration.
4. Đề xuất endpoint.
5. Hiện thực theo đúng chiều phụ thuộc Hexagonal.
6. Thêm validation và xử lý biên.
7. Thêm test — gồm test đồng thời nếu chạm tài nguyên tranh chấp.
8. Cập nhật tài liệu nếu hành vi hoặc quyết định kỹ thuật thay đổi.

## Thuật ngữ — dùng đúng, không dùng từ đồng nghĩa

Ba khoản tiền **khác nhau**, đã từng gây nhầm lẫn thật:

| Từ | Code | Có hoàn không |
|---|---|---|
| **Cọc** | `deposit` | Không — trừ vào tiền thuê |
| **Phần còn lại** | `balance` | Không — là tiền thuê |
| **Thế chấp** | `collateral` | **Có** — hoàn 100% sau khi trả xe |

Đầy đủ ở `glossary.md`, gồm mục "những cặp từ dễ nhầm".
