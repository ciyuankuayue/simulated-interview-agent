package com.bupt.mockinterview.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台 ADMIN 引导配置（R2-T0 D3），前缀 {@code app.security.bootstrap-admin}。
 * <p>
 * 经环境变量 {@code BOOTSTRAP_ADMIN_USERNAME} / {@code BOOTSTRAP_ADMIN_PASSWORD} 注入
 * （application-prod.yml 占位）。username 空 = 不引导（启动仅 WARN 指引）；
 * password 空且 username 已配 = 首建时生成随机密码并 WARN 回显一次。
 */
@Data
@ConfigurationProperties(prefix = "app.security.bootstrap-admin")
public class BootstrapAdminProperties {

    /** 引导管理员用户名；为空则不启用引导。 */
    private String username = "";

    /** 引导管理员密码（明文仅存在于 env/内存，落库前 BCrypt）；为空时首建随机生成。 */
    private String password = "";
}
