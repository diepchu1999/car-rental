---
name: review
description: Review code theo quy trình của repo đang mở — đọc code thật, đo thay vì đoán, đối chiếu quy tắc và ranh giới kiến trúc. Dùng khi cần review một task, một thay đổi, hoặc một phạm vi code cụ thể.
---

# Review

Review code theo đúng quy trình của repo hiện tại.

## Cách làm

1. Đọc file **`.ai/prompts/review.md`** ở gốc repo đang mở.
2. Làm theo đúng quy trình trong đó, không bỏ bước nào.
3. Phạm vi cần review là đầu vào của quy trình đó.

## Nguyên tắc không được bỏ qua

Dù quy trình của repo nói gì, ba điều sau luôn đúng:

- **Đọc code thật trước khi kết luận.** Không suy hành vi từ tên class, tên method hay tên file.
- **Đo thay vì đoán.** Trước khi khẳng định một vấn đề hiệu năng, chạy và đo.
- **Chỉ review, không sửa.** Sản phẩm của review là danh sách phát hiện, không phải một commit.

## Khi repo chưa có file quy ước

Nếu `.ai/prompts/review.md` **không tồn tại**, nói thẳng rằng repo này chưa thiết lập quy ước
review, và hỏi người dùng muốn làm gì. Không tự nghĩ ra một quy trình thay thế.
