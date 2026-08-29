# ADR-0013: Tham số kinh doanh có hiệu lực theo thời gian

- Trạng thái: ACCEPTED · 27/08/2026

## Bối cảnh
BR-225 liệt kê 11 tham số phải chỉnh được mà không phát hành lại: phí tài xế, mức tăng giá cuối tuần
và lễ tết, phí dịch vụ nhiên liệu, phí trễ hạn, ngưỡng tái phạm, trần ứng trả... Cộng thêm danh sách
ngày lễ (BR-218) và bậc chiết khấu (BR-230).

Tech Owner chưa có xe, chưa có khách, chưa có dữ liệu thị trường — mọi con số hiện tại là phỏng đoán
có tham chiếu.

## Quyết định
Tham số kinh doanh lưu dưới dạng **giá trị có khoảng hiệu lực**, không phải một ô đơn lẻ bị ghi đè.
Mỗi bản ghi có giá trị, thời điểm bắt đầu hiệu lực, và người thay đổi.

Danh sách ngày lễ là **dữ liệu**, không phải hằng số trong code — mỗi năm lịch nghỉ khác nhau và
không ai muốn phát hành lại ứng dụng vì Tết rơi vào ngày khác.

## Lý do
Nếu ghi đè giá trị cũ, ta mất khả năng trả lời "lúc khách đặt đơn đó thì phí tài xế là bao nhiêu" —
và đó chính là câu phải trả lời khi khách khiếu nại hoặc khi đối soát với đối tác.

Đây là mặt còn lại của ADR-0014: đơn đóng băng giá trị, còn cấu hình giữ lịch sử. Hai thứ cùng phục
vụ một mục đích — làm cho quá khứ giải thích được.

## Hệ quả
- Đọc tham số luôn kèm mốc thời gian, không đọc "giá trị hiện tại" khi đang xử lý dữ liệu quá khứ.
- Màn hình quản trị phải cho xem lịch sử thay đổi, không chỉ giá trị đang dùng.
