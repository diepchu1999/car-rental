package com.carrental.branch.adapter.in.rest.admin.response;

import com.carrental.branch.application.view.BranchDetail;

import java.util.Objects;

/**
 * Chứa thông tin chi nhánh được công khai qua API quản trị.
 *
 * <p>Vị trí phục vụ BR-003. Mã nghiệp vụ dùng để nhận diện chi nhánh
 * theo database-guideline mục 2.
 *
 * <p>Không đưa khóa chính số của cơ sở dữ liệu vào response.
 * Response tách biệt với application view để giữ rõ hợp đồng HTTP.
 *
 * @param code mã nghiệp vụ của chi nhánh
 * @param latitude vĩ độ tính bằng độ
 * @param longitude kinh độ tính bằng độ
 * @param name tên chi nhánh theo BR-808
 * @param address địa chỉ chi nhánh theo BR-808
 */
public record BranchResponse(
        String code,
        double latitude,
        double longitude,
        String name,
        String address
) {

    /**
     * Chuyển application view thành dữ liệu trả ra qua HTTP.
     *
     * <p>Tên fromDomain tuân theo quy ước của module-architecture.
     * Đầu vào thực tế là BranchDetail, tức read view của application.
     *
     * <p>Chỉ lấy các trường thuộc hợp đồng API,
     * không chuyển khóa chính nội bộ sang response.
     *
     * @param view thông tin chi nhánh do application trả về
     * @return response chứa mã nghiệp vụ và tọa độ
     * @throws NullPointerException nếu view là null
     */
    public static BranchResponse fromDomain(BranchDetail view) {
        Objects.requireNonNull(
                view,
                "view must not be null."
        );

        return new BranchResponse(
                view.code(),
                view.latitude(),
                view.longitude(),
                view.name(),
                view.address()
        );
    }
}
