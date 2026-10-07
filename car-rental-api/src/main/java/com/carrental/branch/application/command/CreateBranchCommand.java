package com.carrental.branch.application.command;

import com.carrental.branch.domain.BranchLocation;
import com.carrental.shared.error.DomainException;
import com.carrental.shared.validation.Validations;

/**
 * Chứa dữ liệu đã kiểm tra cho yêu cầu tạo chi nhánh.
 *
 * <p>Vị trí phục vụ BR-003. Mã chi nhánh không thuộc đầu vào
 * của command vì được application service sinh theo
 * database-guideline mục 2.
 *
 * <p>Factory chỉ nhận giá trị thô, không phụ thuộc DTO của REST adapter.
 *
 * @param location tọa độ hợp lệ của chi nhánh cần tạo
 * @param name tên bắt buộc theo BR-808
 * @param address địa chỉ bắt buộc theo BR-808
 */
public record CreateBranchCommand(
        BranchLocation location,
        String name,
        String address
) {

    /**
     * Bảo đảm command có vị trí, tên và địa chỉ hợp lệ, kể cả khi được tạo trực tiếp.
     *
     * <p>Giới hạn tọa độ đã được BranchLocation bảo vệ.
     *
     * @throws DomainException nếu thiếu vị trí hoặc tên/địa chỉ thiếu, rỗng hay trắng
     */
    public CreateBranchCommand {
        location = Validations.required(location, "location");
        name = Validations.requiredText(name, "name");
        address = Validations.requiredText(address, "address");
    }

    /**
     * Chuyển tọa độ, tên và địa chỉ đầu vào thành command đã được kiểm tra.
     *
     * <p>Ủy quyền cho BranchLocation kiểm dữ liệu thiếu,
     * giá trị không hữu hạn và giới hạn địa lý.
     *
     * @param latitude vĩ độ đầu vào, có thể null nếu bị thiếu
     * @param longitude kinh độ đầu vào, có thể null nếu bị thiếu
     * @param name tên chi nhánh, không được trắng
     * @param address địa chỉ chi nhánh, không được trắng
     * @return command chứa vị trí và thông tin hiển thị hợp lệ
     * @throws DomainException nếu tọa độ sai hoặc tên/địa chỉ thiếu, rỗng hay trắng
     */
    public static CreateBranchCommand from(
            Double latitude,
            Double longitude,
            String name,
            String address
    ) {
        return new CreateBranchCommand(
                BranchLocation.from(latitude, longitude),
                name,
                address
        );
    }
}
