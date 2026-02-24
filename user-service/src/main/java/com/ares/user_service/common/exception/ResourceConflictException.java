package com.ares.user_service.common.exception;

public class ResourceConflictException extends BusinessException {
    public ResourceConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ResourceConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
