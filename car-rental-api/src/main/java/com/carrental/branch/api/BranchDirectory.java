package com.carrental.branch.api;

import java.util.Optional;
import java.util.List;

/**
 * Cung cấp cổng tra cứu chi nhánh cho các module khác.
 *
 * <p>Module vehicle sử dụng cổng này để kiểm tra chi nhánh tồn tại
 * trước khi liên kết xe công ty với chi nhánh theo BR-003.
 *
 * <p>Module gọi chỉ phụ thuộc hợp đồng trong package api,
 * không phụ thuộc domain, application hoặc adapter của branch.
 *
 * <p>Cổng này chỉ đọc dữ liệu, không tạo hoặc thay đổi chi nhánh.
 */
public interface BranchDirectory {

    /**
     * Tìm thông tin định danh của chi nhánh theo mã nghiệp vụ.
     *
     * <p>Mã đầu vào phải khác null và không được trắng.
     * Việc tra cứu giữ nguyên mã, không tự cắt khoảng trắng
     * hoặc chuyển đổi chữ hoa, chữ thường.
     *
     * <p>Kết quả rỗng chỉ biểu thị không tìm thấy chi nhánh.
     * Module gọi quyết định cách xử lý trường hợp này.
     * Lỗi truy cập dữ liệu phải được truyền ra ngoài,
     * không được chuyển thành kết quả rỗng.
     *
     * @param code mã nghiệp vụ của chi nhánh cần tìm
     * @return thông tin chi nhánh nếu tìm thấy; Optional rỗng nếu không có;
     *         không bao giờ trả null
     */
    Optional<BranchRef> findByCode(String code);

    /** Tra định danh bất biến đã lưu ở module khác; ID phải dương, không tìm thấy trả rỗng (ADR-0008). */
    Optional<BranchRef> findById(long id);

    /**
     * Tìm chi nhánh trong bán kính địa lý theo BR-003, BR-808 và ADR-0007.
     * @param latitude vĩ độ tâm tìm kiếm, bắt buộc và thuộc [-90, 90]
     * @param longitude kinh độ tâm tìm kiếm, bắt buộc và thuộc [-180, 180]
     * @param radiusMeters bán kính hữu hạn, dương theo mét; search áp mặc định/trần BR-125
     * @return danh sách bất biến theo khoảng cách rồi ID, có thể rỗng; lỗi không bị nuốt
     */
    List<BranchSearchView> findWithinRadius(Double latitude, Double longitude, Double radiusMeters);
}
