package com.carrental.vehicle.domain;

/**
 * Biểu diễn loại nhiên liệu của xe theo BR-410.
 *
 * <p>Loại nhiên liệu là thuộc tính của từng xe.
 * Task hiện tại chỉ lưu và cung cấp thông tin này.
 *
 * <p>Các chính sách nhiên liệu khi giao và trả xe
 * được hiện thực ở task tương ứng, không đặt trong enum này.
 */
public enum FuelType {

    /**
     * Xe sử dụng xăng.
     */
    PETROL,

    /**
     * Xe sử dụng dầu diesel.
     */
    DIESEL,

    /**
     * Xe sử dụng điện.
     */
    ELECTRIC,

    /**
     * Xe kết hợp động cơ đốt trong và động cơ điện.
     */
    HYBRID
}