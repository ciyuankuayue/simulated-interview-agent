package com.bupt.mockinterview.web;

import com.bupt.mockinterview.domain.User;
import com.bupt.mockinterview.domain.UserRole;
import com.bupt.mockinterview.rag.OrphanIndexInspector;
import com.bupt.mockinterview.security.CurrentUserService;
import com.bupt.mockinterview.security.ForbiddenException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量索引 ADMIN 巡检/回收（R2-T0 批 5，C3；api.md §7）。
 * warn-first：GET 只报告；POST 单点回收且删前复验仍为孤儿（健康来源 → 409）。
 * 鉴权仍只有 JWT 一道，角色在方法内显式校验（非 ADMIN → 403）。
 */
@RestController
@RequestMapping("/api/admin/index")
public class AdminIndexController {

    private final CurrentUserService currentUser;
    private final OrphanIndexInspector inspector;

    public AdminIndexController(CurrentUserService currentUser, OrphanIndexInspector inspector) {
        this.currentUser = currentUser;
        this.inspector = inspector;
    }

    /** 巡检孤儿向量来源：不带 tenantId = 全租户（跨租户白名单），带 = 单租户。 */
    @GetMapping("/orphans")
    public Map<String, Object> orphans(@RequestParam(name = "tenantId", required = false) Long tenantId) {
        requireAdmin();
        List<OrphanIndexInspector.OrphanSource> orphans = inspector.listOrphans(tenantId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", tenantId == null ? "ALL" : "TENANT");
        body.put("count", orphans.size());
        body.put("orphans", orphans);
        return body;
    }

    /** 人工确认后回收单个孤儿来源；索引无此来源 → 404，来源仍有知识块行 → 409。 */
    @PostMapping("/delete-by-source")
    public Map<String, Object> deleteBySource(@Valid @RequestBody DeleteOrphanRequest req) {
        requireAdmin();
        OrphanIndexInspector.DeleteResult result =
                inspector.deleteOrphan(req.tenantId(), req.sourceType(), req.sourceId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("tenantId", req.tenantId());
        body.put("sourceType", req.sourceType());
        body.put("sourceId", req.sourceId());
        body.put("chunksRemoved", result.chunksRemoved());
        return body;
    }

    private void requireAdmin() {
        User operator = currentUser.currentUser();
        if (operator == null || operator.getRole() != UserRole.ADMIN) {
            throw new ForbiddenException("仅管理员可巡检/回收向量索引");
        }
    }

    /** 回收请求：三项必填；tenantId 必须显式（禁止匿名/隐式全量删）。 */
    public record DeleteOrphanRequest(
            @NotNull Long tenantId,
            @NotBlank String sourceType,
            @NotBlank String sourceId) {
    }
}
