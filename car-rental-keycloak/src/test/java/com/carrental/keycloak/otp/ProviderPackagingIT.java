package com.carrental.keycloak.otp;

import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

/** Kiểm artifact thật sau package: provider Java 21 và tách tuyệt đối SMS giả/fixture HTTP. */
class ProviderPackagingIT {
    /** JAR thật không mang endpoint probe hay adapter log OTP, kể cả qua ServiceLoader. */
    @Test void coreJarExcludesLocalSmsAndTestProbe() throws Exception {
        try (var jar = new JarFile(System.getProperty("provider.jar"))) {
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().contains("/smslocal/") || entry.getName().contains("/probe/")));
            assertNull(jar.getEntry("META-INF/services/com.carrental.keycloak.otp.SmsSender"));
            assertNull(jar.getEntry("META-INF/services/org.keycloak.services.resource.RealmResourceProviderFactory"));
            assertNotNull(jar.getEntry("META-INF/car-rental-otp-changelog.xml"));
            assertNotNull(jar.getEntry("META-INF/services/org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory"));
            assertNotNull(jar.getEntry("META-INF/services/org.keycloak.authentication.AuthenticatorFactory"));
            assertNotNull(jar.getEntry("META-INF/services/org.keycloak.authentication.RequiredActionFactory"));
            assertNotNull(jar.getEntry("META-INF/keycloak-themes.json"));
            assertNotNull(jar.getEntry("theme/car-rental/login/car-rental-sms.ftl"));
            assertNotNull(jar.getEntry("theme/car-rental/login/car-rental-phone.ftl"));
            assertNotNull(jar.getEntry("theme/car-rental/login/car-rental-terms.ftl"));
            assertNotNull(jar.getEntry("terms/local-test-terms.txt"));
            assertNotNull(jar.getEntry("sql/car-rental-identity/lock_registration.sql"));
            assertNotNull(jar.getEntry("com/carrental/keycloak/internal/phonenumber/PhoneNumberUtil.class"));
            assertNull(jar.getEntry("org/keycloak/models/KeycloakSession.class"));
        }
    }

    /** Tệp class lõi phải là Java 21, không vô tình nhận release 25 từ backend. */
    @Test void targetsJava21Bytecode() throws Exception {
        try (var jar = new JarFile(System.getProperty("provider.jar"));
             var input = new DataInputStream(jar.getInputStream(jar.getEntry("com/carrental/keycloak/otp/PostgresOtpProvider.class")))) {
            assertEquals(0xCAFEBABE, input.readInt()); input.readUnsignedShort();
            assertEquals(65, input.readUnsignedShort());
        }
    }

    /** Hai artifact phụ chứa đúng vai trò của chúng, không dùng test JAR để chạy local. */
    @Test void keepsLocalSmsAndProbeInSeparateArtifacts() throws Exception {
        try (var sms = new JarFile(System.getProperty("local.sms.jar")); var probe = new JarFile(System.getProperty("probe.jar"))) {
            assertNotNull(sms.getEntry("com/carrental/keycloak/smslocal/LocalLogSmsSender.class"));
            assertNotNull(sms.getEntry("META-INF/services/com.carrental.keycloak.otp.SmsSender"));
            assertFalse(sms.stream().anyMatch(entry -> entry.getName().contains("/probe/")));
            assertNotNull(probe.getEntry("com/carrental/keycloak/probe/OtpProbeFactory.class"));
            assertNotNull(probe.getEntry("META-INF/beans.xml"));
            assertFalse(probe.stream().anyMatch(entry -> entry.getName().contains("/smslocal/")));
        }
    }
}
