# car-rental-docs

Tài liệu là nguồn sự thật duy nhất của dự án. Quy tắc quan trọng không được phép chỉ nằm trong chat.

```text
vi/
├── business/       nghiệp vụ — Tech Owner chốt
├── architecture/   kiến trúc — Chief Architect soạn
├── api/            danh mục endpoint theo module
└── decisions/      ADR đánh số, không xoá, chỉ chuyển trạng thái
```

## Đọc theo thứ tự nào

**Lần đầu vào dự án**
1. [`../CLAUDE.md`](../CLAUDE.md) — quy tắc làm việc
2. [`vi/architecture/architecture-overview.md`](vi/architecture/architecture-overview.md) — hệ thống là gì, bảy rủi ro kỹ thuật
3. [`vi/business/glossary.md`](vi/business/glossary.md) — thuật ngữ, nhất là ba khoản tiền
4. ADR-0005 và ADR-0004 — hai quyết định định hình mọi thứ

**Trước dòng code đầu tiên**
5. [`vi/architecture/module-architecture.md`](vi/architecture/module-architecture.md) — chuẩn bắt buộc
6. [`vi/architecture/database-guideline.md`](vi/architecture/database-guideline.md) — nhất là §4, bảng khoá lịch
7. [`vi/architecture/development-guideline.md`](vi/architecture/development-guideline.md) — định nghĩa Done

## Business

| File | Nội dung |
|---|---|
| [`business-rules.md`](vi/business/business-rules.md) | **149 quy tắc** đã chốt, và mục "chưa chốt" ở cuối |
| [`glossary.md`](vi/business/glossary.md) | Từ điển thuật ngữ + những cặp từ dễ nhầm |
| [`domain-map.md`](vi/business/domain-map.md) | 16 vùng nghiệp vụ, 10 bất biến, 4 ranh giới phải giữ sạch |
| [`status-flow.md`](vi/business/status-flow.md) | 8 máy trạng thái + bất biến xuyên thực thể |
| [`requirements-analysis.md`](vi/business/requirements-analysis.md) | Biên bản phân tích 9 vòng phỏng vấn — nguồn gốc của các quy tắc |

### Nhóm mã BR
`0xx` xe & nguồn cung · `1xx` tìm và đặt xe · `2xx` tiền · `3xx` huỷ & đổi xe ·
`4xx` bàn giao & trả xe · `5xx` tài xế · `6xx` bảo hiểm · `7xx` phạt nguội ·
`8xx` chi nhánh & phân quyền · `9xx` đánh giá · `10xx` thông báo.

**Mã không bao giờ tái sử dụng.** Quy tắc bị bỏ thì đánh dấu `[ĐÃ HUỶ]`, không xoá.

## Architecture

| File | Nội dung |
|---|---|
| [`architecture-overview.md`](vi/architecture/architecture-overview.md) | Toàn cảnh, bảy rủi ro kỹ thuật suy ra từ rules |
| [`module-map.md`](vi/architecture/module-map.md) | 20 module, phụ thuộc, 8 điều cấm tuyệt đối |
| [`module-architecture.md`](vi/architecture/module-architecture.md) | Chuẩn code bắt buộc, 10 rule khoá bằng test |
| [`backend-guideline.md`](vi/architecture/backend-guideline.md) | Transaction, mã lỗi, 8 kịch bản test đồng thời |
| [`database-guideline.md`](vi/architecture/database-guideline.md) | DDL bảng khoá lịch, PostGIS, cấu hình theo thời gian |
| [`api-guideline.md`](vi/architecture/api-guideline.md) | REST, namespace, versioning do mobile áp đặt |
| [`security-guideline.md`](vi/architecture/security-guideline.md) | Auth, chống truy cập chéo, ai thấy gì trên một đơn |
| [`frontend-guideline.md`](vi/architecture/frontend-guideline.md) | React + TS, hai app |
| [`mobile-guideline.md`](vi/architecture/mobile-guideline.md) | React Native |
| [`development-guideline.md`](vi/architecture/development-guideline.md) | Quy trình, định nghĩa Done |

## ADR

Trạng thái: `PROPOSED` → `ACCEPTED` → `SUPERSEDED` hoặc `DEPRECATED`.
**Không sửa ADR đã ACCEPTED để đổi ý** — viết ADR mới và đánh dấu cái cũ SUPERSEDED. Lịch sử vì sao
ta từng quyết định như vậy có giá trị riêng.

| ADR | Nội dung | Trạng thái |
|---|---|---|
| [0001](vi/decisions/adr-0001-modular-monolith.md) | Modular Monolith | ACCEPTED |
| [0002](vi/decisions/adr-0002-java-spring-boot.md) | Java + Spring Boot | ACCEPTED |
| [0003](vi/decisions/adr-0003-postgresql-native-sql.md) | PostgreSQL + Native SQL, không JPA | ACCEPTED |
| [0004](vi/decisions/adr-0004-clean-hexagonal-and-policy.md) | **Hexagonal + phân giải policy hai trục** | ACCEPTED |
| [0005](vi/decisions/adr-0005-exclusion-constraint-scheduling.md) | **Ràng buộc loại trừ chống trùng lịch** | ACCEPTED |
| [0006](vi/decisions/adr-0006-keycloak-with-sms-otp.md) | Keycloak + Authenticator OTP qua SMS | ACCEPTED |
| [0007](vi/decisions/adr-0007-postgis-geo-search.md) | PostGIS cho tìm kiếm theo vị trí | ACCEPTED |
| [0008](vi/decisions/adr-0008-schema-per-module.md) | Schema per module, không FK chéo | ACCEPTED |
| [0009](vi/decisions/adr-0009-react-native.md) | React Native cho mobile | ACCEPTED |
| [0010](vi/decisions/adr-0010-frontend-split.md) | Tách customer-web và admin-web | ACCEPTED |
| [0011](vi/decisions/adr-0011-payment-port-manual-adapter.md) | Payment port, adapter xác nhận thủ công | ACCEPTED |
| [0012](vi/decisions/adr-0012-vertical-slice-delivery.md) | Xây theo lát cắt dọc | ACCEPTED |
| [0013](vi/decisions/adr-0013-time-effective-configuration.md) | Cấu hình có hiệu lực theo thời gian | ACCEPTED |
| [0014](vi/decisions/adr-0014-per-booking-snapshot.md) | **Đơn đóng băng mọi giá trị** | ACCEPTED |
| [0015](vi/decisions/adr-0015-immutable-evidence.md) | **Hồ sơ bằng chứng bất biến** | ACCEPTED |

## API

- [API chi nhánh — admin](vi/api/branch.md): tạo và đọc chi nhánh theo mã nghiệp vụ.
- [API xe — admin](vi/api/vehicle.md): tạo, đọc, gửi duyệt và duyệt xe công ty.
- [Bàn giao Task 4](vi/dev-notes/task-04-shared-branch-vehicle.md): phạm vi, quyết định kỹ thuật và hướng dẫn nghiệm thu.

## Chưa có

`vi/modules/` — đặc tả từng module, viết khi bắt đầu làm module đó.
`vi/dev-notes/` — ghi chú vận hành local.
