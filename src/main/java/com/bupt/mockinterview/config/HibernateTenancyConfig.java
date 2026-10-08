package com.bupt.mockinterview.config;

import com.bupt.mockinterview.security.CurrentTenantResolver;
import com.bupt.mockinterview.security.TenantProperties;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * R2-T0 D1：注册 Hibernate 6 分区（discriminator）多租户解析器。
 * <p>
 * 实体字段标注 {@link org.hibernate.annotations.TenantId} 后，Hibernate 自动按
 * {@link CurrentTenantResolver} 的返回值在 SELECT/UPDATE/DELETE（含批量 JPQL）追加
 * tenant_id 谓词、在 INSERT 时自动回填，无需手写 WHERE/@Filter。
 */
@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class HibernateTenancyConfig {

    @Bean
    public HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(
            CurrentTenantResolver resolver) {
        return hibernateProperties ->
                hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
