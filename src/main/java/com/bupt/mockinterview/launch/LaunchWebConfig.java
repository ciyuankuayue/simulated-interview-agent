package com.bupt.mockinterview.launch;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LaunchWebConfig implements WebMvcConfigurer {
    private final LaunchRequestInterceptor interceptor;
    public LaunchWebConfig(LaunchRequestInterceptor interceptor) { this.interceptor = interceptor; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/api/**"); }
}
