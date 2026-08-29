package com.carrental.vehicle.domain;

/**
 * Biểu diễn loại sở hữu của xe theo BR-001.
 *
 * <p>Loại sở hữu được xác định khi tạo xe và không được thay đổi
 * trong suốt vòng đời bản ghi.
 *
 * <p>Enum khai báo đầy đủ hai giá trị theo mô hình nghiệp vụ.
 * API tạo xe trong giai đoạn 1 chỉ gán COMPANY.
 *
 * <p>Enum không chứa logic phân giải chính sách hoặc rẽ nhánh
 * theo loại sở hữu. Việc phân giải chính sách phải tuân theo
 * ADR-0004 và quy tắc kiến trúc R6.
 */
public enum OwnershipType {

    /**
     * Xe thuộc đội xe của công ty.
     */
    COMPANY,

    /**
     * Xe thuộc đối tác cung cấp trên nền tảng.
     */
    PARTNER
}