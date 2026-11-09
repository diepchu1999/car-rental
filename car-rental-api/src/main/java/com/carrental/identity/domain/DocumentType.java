package com.carrental.identity.domain;

/** Hai loại giấy tờ khách tự nộp và phải được xác minh theo BR-120/130. */
public enum DocumentType {
    /** Căn cước công dân; một số chỉ được xác minh cho một tài khoản theo BR-130. */
    IDENTITY_CARD,
    /** Giấy phép lái xe; hạng và hạn chế hộp số được xét theo BR-128. */
    DRIVER_LICENSE
}
