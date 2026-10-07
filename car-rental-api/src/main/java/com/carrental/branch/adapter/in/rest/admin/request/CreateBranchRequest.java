package com.carrental.branch.adapter.in.rest.admin.request;

/**
 * Chứa dữ liệu JSON đầu vào của yêu cầu tạo chi nhánh.
 *
 * <p>Vị trí chi nhánh phục vụ BR-003.
 * Request không nhận mã chi nhánh vì mã được application service sinh.
 *
 * <p>Dữ liệu tại đây chưa được kiểm tra.
 * Controller sẽ truyền các giá trị thô vào CreateBranchCommand.from
 * để thực hiện validation tại tầng application và domain.
 *
 * <p>Dùng Double để biểu diễn được giá trị null,
 * phân biệt tọa độ bị thiếu với tọa độ bằng không.
 *
 * @param latitude vĩ độ tính bằng độ, có thể null nếu đầu vào thiếu
 * @param longitude kinh độ tính bằng độ, có thể null nếu đầu vào thiếu
 * @param name tên chi nhánh theo BR-808
 * @param address địa chỉ chi nhánh theo BR-808
 */
public record CreateBranchRequest(
        Double latitude,
        Double longitude,
        String name,
        String address
) {
}
