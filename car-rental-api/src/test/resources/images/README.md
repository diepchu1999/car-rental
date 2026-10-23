# Ảnh phục vụ kiểm parser

`heic-2x2.base64` là biểu diễn base64 nguyên vẹn của fixture HEIC 2×2
`tests/data/conformance_window_padding.heic` từ libheif, commit
`35d118798a883be30c8b44086f85fa5b5fc7e6e3`:
https://github.com/strukturag/libheif/blob/35d118798a883be30c8b44086f85fa5b5fc7e6e3/tests/data/conformance_window_padding.heic

Chỉ dùng trong test, không đóng gói main. Giấy phép upstream ở `libheif-COPYING.txt`.
Không phải ảnh giấy tờ hoặc dữ liệu khách hàng. PNG/JPEG của test được tự vẽ trong Java.
Đây là test nhận dạng container HEIC; không khẳng định backend giải mã codec HEVC hay làm OCR.
