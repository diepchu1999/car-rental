---
name: task
description: Làm một việc sửa hoặc thêm tính năng trong repo đang mở, theo quy trình Principal Engineer của repo — đối chiếu business rule, trình bày kế hoạch chờ duyệt, chia nhỏ từng phần, viết code và hướng dẫn người dùng tự chạy test, Postman, SQL.
---

# Task

Làm việc người dùng giao theo đúng quy trình của repo hiện tại.

## Cách làm

1. Đọc file **`.ai/prompts/implement.md`** ở gốc repo đang mở.
2. Làm theo đúng quy trình trong đó, không bỏ bước nào — nhất là đối chiếu business rule,
   kế hoạch chờ duyệt, và dừng sau mỗi phần.
3. Yêu cầu cụ thể của người dùng là đầu vào của quy trình đó. Nếu người dùng dán một prompt task
   đầy đủ, prompt đó bổ sung cho quy trình, không thay thế nó.

## Khi repo chưa có file quy trình

Nếu `.ai/prompts/implement.md` **không tồn tại**, nói thẳng rằng repo này chưa thiết lập quy trình,
và hỏi người dùng muốn làm gì.

**Không tự nghĩ ra một quy trình thay thế.** Làm theo khuôn tự chế trông giống làm đúng chuẩn nhưng
thiếu những chốt chặn mà repo cần — tệ hơn là không có skill.
