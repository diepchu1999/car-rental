package com.carrental.identity.domain;

/** BR-130: trạng thái từng lần nộp, độc lập với con trỏ bản VERIFIED đang dùng ở document_head. */
public enum DocumentSubmissionStatus {
    PENDING,
    VERIFIED,
    REJECTED,
    SUPERSEDED
}
