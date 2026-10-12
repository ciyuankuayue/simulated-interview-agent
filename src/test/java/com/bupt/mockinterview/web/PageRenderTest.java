package com.bupt.mockinterview.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T9 验收（服务端渲染部分）：页面骨架能渲染 + 静态资源能拿到。
 *
 * 为什么值得测：**Thymeleaf 模板写错只有真正渲染时才炸**（编译期完全看不出来），
 * 而前端是无构建链的原生 JS，静态资源 404 也不会有任何报错提示。这里把这两件事钉死。
 *
 * ⚠️ 读响应必须显式 UTF-8：MockHttpServletResponse 默认 ISO-8859-1，中文会乱码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageRenderTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void index_rendersSkeleton() throws Exception {
        String html = mvc.perform(get("/"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // F12 Vue3 重构后：index.html 只负责挂载点 + 静态资源，业务文本在 app.js 组件模板里
        assertThat(html)
                .contains("AI 模拟面试")
                .contains("id=\"app\"")
                .contains("vue.global")   // Vue3 CDN
                .contains("/app.js")
                .contains("/style.css");
    }

    @Test
    void staticAssets_areServed() throws Exception {
        String js = mvc.perform(get("/app.js"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(js)
                .contains("EventSource")
                .contains("/api/interview/")
                .contains("/api/weak-point")
                // F12：业务文本现在在组件模板里
                .contains("开始模拟面试")
                .contains("技术深挖官")
                .contains("项目质疑官")
                .contains("错题本")
                .contains("Critic 报告")
                // F10：心理陪伴
                .contains("/api/companion/chat");

        String css = mvc.perform(get("/style.css"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(css).contains("--bg");
    }
}
