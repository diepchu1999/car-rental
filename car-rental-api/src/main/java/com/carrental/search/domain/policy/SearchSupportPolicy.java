package com.carrental.search.domain.policy;

/** Chính sách hỗ trợ của lát cắt hiện tại theo BR-125/126, đã được resolver phân giải. */
@FunctionalInterface
public interface SearchSupportPolicy {
    /** Báo lỗi nghiệp vụ rõ ràng nếu lựa chọn chưa được triển khai; không trả danh sách rỗng. */
    void validate();
}
