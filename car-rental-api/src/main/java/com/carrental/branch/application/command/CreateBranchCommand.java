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
 */
public record CreateBranchCommand(
        BranchLocation location
) {

    /**
     * Bảo đảm command luôn có vị trí, kể cả khi được tạo trực tiếp.
     *
     * <p>Giới hạn tọa độ đã được BranchLocation bảo vệ.
     *
     * @throws DomainException nếu vị trí là null
     */
    public CreateBranchCommand {
        location = Validations.required(location, "location");
    }

    /**
     * Chuyển các tọa độ đầu vào thành command đã được kiểm tra.
     *
     * <p>Ủy quyền cho BranchLocation kiểm dữ liệu thiếu,
     * giá trị không hữu hạn và giới hạn địa lý.
     *
     * @param latitude vĩ độ đầu vào, có thể null nếu bị thiếu
     * @param longitude kinh độ đầu vào, có thể null nếu bị thiếu
     * @return command chứa vị trí hợp lệ
     * @throws DomainException nếu thiếu tọa độ hoặc tọa độ không hợp lệ
     */
    public static CreateBranchCommand from(
            Double latitude,
            Double longitude
    ) {
        return new CreateBranchCommand(
                BranchLocation.from(latitude, longitude)
        );
    }
}