package com.bupt.mockinterview.launch;

import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.UserContext;
import com.bupt.mockinterview.web.MaterialController;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LaunchProtectionTest {
    @AfterEach void clear() { UserContext.clear(); }
    @Test void overlappingModelOperationsAreBlockedAcrossFeaturesAndReleasedAfterFailure() {
        var limiter = new LaunchAccessLimiter(null, new MockEnvironment());
        var interceptor = new LaunchRequestInterceptor(new LaunchLimits(), limiter);
        UserContext.set(21L, "test");
        var first = request("/api/interview/start");
        assertThat(interceptor.preHandle(first, new MockHttpServletResponse(), this)).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request("/api/coach/stream"), new MockHttpServletResponse(), this))
                .isInstanceOfSatisfying(LaunchLimitException.class, e -> assertThat(e.status()).isEqualTo(409));
        UserContext.set(22L, "different");
        var other = request("/api/coach/stream");
        assertThat(interceptor.preHandle(other, new MockHttpServletResponse(), this)).isTrue();
        interceptor.afterCompletion(first, new MockHttpServletResponse(), this, new RuntimeException());
        UserContext.set(21L, "test");
        var retry = request("/api/coach/stream");
        assertThat(interceptor.preHandle(retry, new MockHttpServletResponse(), this)).isTrue();
        interceptor.afterCompletion(other, new MockHttpServletResponse(), this, null);
        interceptor.afterCompletion(retry, new MockHttpServletResponse(), this, null);
    }
    @Test void callerCannotEvadeRegistrationLimitByForgingForwardedAddress() {
        var limits = new LaunchLimits(); limits.setRegistrationsPerHour(1);
        var interceptor = new LaunchRequestInterceptor(limits, new LaunchAccessLimiter(null, new MockEnvironment()));
        var first = request("/api/auth/register"); first.addHeader("X-Forwarded-For", "1.2.3.4");
        interceptor.preHandle(first, new MockHttpServletResponse(), this);
        var second = request("/api/auth/register"); second.addHeader("X-Forwarded-For", "5.6.7.8");
        assertThatThrownBy(() -> interceptor.preHandle(second, new MockHttpServletResponse(), this))
                .isInstanceOfSatisfying(LaunchLimitException.class, e -> assertThat(e.status()).isEqualTo(429));
    }
    @Test void chunkedBodyIsBoundedWithoutTrustingContentLength() throws Exception {
        var limits = new LaunchLimits(); limits.setMaxRequestBytes(16);
        var advice = new LaunchBodyAdvice(limits, mock(EntityManager.class), mock(CurrentUserService.class));
        var input = new MockHttpInputMessage("this body exceeds sixteen bytes".getBytes(StandardCharsets.UTF_8));
        var bounded = advice.beforeBodyRead(input, null, null, null);
        assertThatThrownBy(() -> bounded.getBody().readAllBytes()).isInstanceOf(LaunchLimitException.class);
    }
    @Test void materialSizeIsCheckedBeforeModelOrStorageWork() {
        var limits = new LaunchLimits(); limits.setMaxTextChars(3);
        var em = mock(EntityManager.class);
        var advice = new LaunchBodyAdvice(limits, em, mock(CurrentUserService.class));
        assertThatThrownBy(() -> advice.afterBodyRead(new MaterialController.ResumeRequest("1234"), null, null, null, null))
                .isInstanceOfSatisfying(LaunchLimitException.class, e -> assertThat(e.code()).isEqualTo("MATERIAL_LIMIT"));
        verifyNoInteractions(em);
    }
    @Test void prodRedisFailureStopsNewWorkRatherThanSilentlyDroppingProtection() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        var limiter = new LaunchAccessLimiter(null, env);
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOfSatisfying(LaunchLimitException.class,
                e -> assertThat(e.status()).isEqualTo(503));
    }
    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path); request.setRemoteAddr("127.0.0.1"); return request;
    }
}
