package com.carrental.search.application.view;

/** Thông tin chi nhánh hiển thị BR-808, không mang ID nội bộ ra response sau này. */
public record SearchBranchSummary(String code, String name, String address) {
}
