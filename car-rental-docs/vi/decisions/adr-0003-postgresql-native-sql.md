# ADR-0003: PostgreSQL + Native SQL, không dùng JPA repository

- Trạng thái: ACCEPTED · 27/08/2026 · Tech Owner

## Quyết định
- PostgreSQL là CSDL chính.
- Truy vấn nghiệp vụ viết bằng native SQL qua `NamedParameterJdbcTemplate`.
- **Không** dùng JPA repository cho truy vấn nghiệp vụ trừ khi có ADR sau cho phép.
- Flyway cho migration. Không sửa migration đã chạy.

## Lý do
Ba thứ khó nhất của hệ thống này đều nằm ở tầng SQL, và đều là tính năng đặc thù của PostgreSQL mà
JPA che mất hoặc làm khó dùng đúng:

| Cần | Tính năng PostgreSQL | Quy tắc |
|---|---|---|
| Chống trùng lịch | `EXCLUDE USING gist` trên `tstzrange` | BR-104 |
| Tìm xe theo vị trí | PostGIS `ST_DWithin` + GIST index | BR-125 |
| Trừ hạn mức mã giảm giá nguyên tử | `UPDATE ... WHERE used_count < max` | BR-227 |

Khi phần khó nhất nằm ở SQL, viết SQL tường minh là ưu điểm chứ không phải gánh nặng. JPA giúp
tiết kiệm ở phần CRUD — vốn là phần dễ nhất và rủi ro thấp nhất của hệ thống này.

## Hệ quả
- Mọi câu SQL nằm trong adapter persistence, không rò lên application hay domain.
- Nhiều code lặp hơn cho CRUD. Chấp nhận.
- Test phải chạy trên PostgreSQL thật (Testcontainers), **không dùng H2** — H2 không có exclusion
  constraint, PostGIS hay `tstzrange`, nên test sẽ xanh trong khi production hỏng.
