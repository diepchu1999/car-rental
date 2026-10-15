package com.carrental.keycloak.otp;

import org.keycloak.provider.ProviderFactory;

/** Hợp đồng factory để Keycloak quản lý vòng đời kho OTP như các provider sẵn có. */
public interface OtpProviderFactory extends ProviderFactory<OtpProvider> { }
