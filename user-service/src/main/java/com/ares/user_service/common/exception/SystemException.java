package com.ares.user_service.common.exception;

public class SystemException extends RuntimeException{
    private final ErrorCode errorCode;

    public SystemException(ErrorCode errorCode, String message){
        super(message);
        this.errorCode = errorCode;
    }

    public SystemException(ErrorCode errorCode){
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
