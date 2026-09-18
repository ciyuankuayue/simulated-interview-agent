package com.bupt.mockinterview.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由（T9，决策 #12：Thymeleaf 服务端渲染 + SSE，**不引入 npm / 任何前端构建链**）。
 *
 * 职责极简：只返回页面骨架，**不含任何业务逻辑**——所有数据都通过 {@code /api/**}
 * 的 JSON 接口拿（与 api.md 契约完全一致），交互逻辑全在 {@code static/app.js}。
 *
 * 这样分的好处：前端只是 api.md 契约的一个消费者，web 层改协议时不用改模板，
 * 后端同学（本项目的使用者）也不用碰前端构建链。
 */
@Controller
public class PageController {

    /** 单页应用：准备资料 / 面试 / 报告 / 错题本 全在这一页，靠 JS 分区切换。 */
    @GetMapping("/")
    public String index() {
        return "index";
    }
}
