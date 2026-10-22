package com.carrental.keycloak.authentication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import static org.junit.jupiter.api.Assertions.*;

/** Khoá cấu hình provider: hai bước phải REQUIRED và metadata cần có để Keycloak tìm ra chúng. */
class EmployeeAuthenticatorFactoryTest {
    /** Factory không quảng bá khả năng bỏ qua password/SMS hoặc tự thiết lập một yếu tố thay thế. */
    @Test void factoriesRequireBothFactorsWithoutPasskeyAlternative() {
        var password = new EmployeePasswordAuthenticatorFactory();
        var sms = new EmployeeSmsAuthenticatorFactory();
        for (AuthenticatorFactory factory : List.of(password, sms)) {
            assertArrayEquals(new Requirement[] {Requirement.REQUIRED}, factory.getRequirementChoices());
            assertFalse(factory.isUserSetupAllowed());
            assertFalse(factory.isConfigurable());
            assertTrue(factory.getConfigProperties().isEmpty());
        }
        assertTrue(password.getOptionalReferenceCategories(null).isEmpty());
        assertInstanceOf(EmployeePasswordAuthenticator.class, password.create(null));
        assertInstanceOf(EmployeeSmsAuthenticator.class, sms.create(null));
        assertTrue(sms.create(null).requiresUser());
    }

    /** SPI và template được đóng dưới main resources, không phụ thuộc test JAR để đăng nhập. */
    @Test void declaresFactoriesAndThemeResources() throws Exception {
        String descriptor = resource("META-INF/services/org.keycloak.authentication.AuthenticatorFactory");
        assertTrue(descriptor.lines().anyMatch(EmployeePasswordAuthenticatorFactory.class.getName()::equals));
        assertTrue(descriptor.lines().anyMatch(EmployeeSmsAuthenticatorFactory.class.getName()::equals));
        assertTrue(descriptor.lines().anyMatch(CustomerOtpAuthenticatorFactory.class.getName()::equals));
        assertTrue(resource("META-INF/keycloak-themes.json").contains("car-rental"));
        String template = resource("theme/car-rental/login/car-rental-sms.ftl");
        assertTrue(template.contains("${url.loginAction}"));
        assertFalse(template.contains("value=\"${otp"));
        assertTrue(resource("theme/car-rental/login/messages/messages_vi.properties").contains("carRentalOtpTitle="));
    }

    /** Đọc đúng resource trong classpath, báo rõ nếu chưa được Maven đưa vào artifact. */
    private String resource(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
