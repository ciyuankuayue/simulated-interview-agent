package com.bupt.mockinterview.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2-T0 批 2（D4）：{@code application-prod.yml} 生产口径装配冒烟（L1，只读 YAML，不起容器）。
 * 锁定三项安全键值，防止后续误改把生产降回 ddl-auto:update / 非 strict / 启动清索引。
 */
class ProdProfileAssemblyTest {

    private Properties prodYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-prod.yml"));
        Properties props = yaml.getObject();
        assertThat(props).as("application-prod.yml 必须存在且可解析").isNotNull();
        return props;
    }

    @Test
    void prod_usesNoDdlAuto_andFlywayManagesSchema() {
        Properties p = prodYaml();
        assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none");
        assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(p.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("true");
        assertThat(p.getProperty("spring.flyway.baseline-version")).isEqualTo("3");
    }

    @Test
    void prod_enablesTenantStrict_andNeverCleansIndex() {
        Properties p = prodYaml();
        assertThat(p.getProperty("app.tenant.strict")).isEqualTo("true");
        assertThat(p.getProperty("app.rag.clean-on-empty-sources")).isEqualTo("false");
    }

    @Test
    void prod_requiresSecretsAndMysqlPassword_fromEnvWithoutDefaults() {
        Properties p = prodYaml();
        // 无默认值的 ${ENV} 占位：env 缺失时 Spring 解析失败即启动失败（fail-fast 的第二道保险）
        assertThat(p.getProperty("jwt.secret")).isEqualTo("${JWT_SECRET}");
        assertThat(p.getProperty("app.security.data-key")).isEqualTo("${APP_DATA_KEY}");
        assertThat(p.getProperty("spring.datasource.password")).isEqualTo("${MYSQL_PASSWORD}");
    }
}
