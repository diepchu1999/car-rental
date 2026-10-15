package com.carrental.keycloak.otp;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/** Đăng ký SPI riêng, không thay thế nhà cung cấp đăng nhập mặc định của Keycloak. */
public final class OtpSpi implements Spi {
    /** Constructor công khai cho ServiceLoader của Keycloak. */
    public OtpSpi() { }
    /** SPI ứng dụng, không phải thành phần nội bộ của Keycloak. */
    @Override public boolean isInternal() { return false; }
    /** Tên cấu hình ổn định của SPI. */
    @Override public String getName() { return "car-rental-otp"; }
    /** Loại provider được lấy từ KeycloakSession. */
    @Override public Class<? extends Provider> getProviderClass() { return OtpProvider.class; }
    /** Loại factory được nạp qua dịch vụ Java. */
    @Override public Class<? extends ProviderFactory> getProviderFactoryClass() { return OtpProviderFactory.class; }
}
