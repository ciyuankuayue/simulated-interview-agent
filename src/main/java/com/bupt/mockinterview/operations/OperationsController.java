package com.bupt.mockinterview.operations;

import static com.bupt.mockinterview.operations.OperationDtos.*;

import com.bupt.mockinterview.domain.*;
import com.bupt.mockinterview.security.*;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class OperationsController {
    private final CurrentUserService current;
    private final OperationsService service;
    private final ProductEventService events;
    private final OperationsQueries queries;

    public OperationsController(
            CurrentUserService current,
            OperationsService service,
            ProductEventService events,
            OperationsQueries queries) {
        this.current = current;
        this.service = service;
        this.events = events;
        this.queries = queries;
    }

    private User user() {
        var u = current.currentUser();
        if (u == null) throw new OperationException(401, "UNAUTHORIZED", "请先登录");
        return u;
    }

    private User admin() {
        var u = user();
        if (u.getRole() != UserRole.ADMIN) throw new ForbiddenException("仅管理员可访问运营管理");
        return u;
    }

    @GetMapping("/api/user/grants")
    public Object grants(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.grants(user().getId(), page, size);
    }

    @GetMapping("/api/user/analytics-preference")
    public Object preference() {
        return service.preference(user().getId(), null);
    }

    @PutMapping("/api/user/analytics-preference")
    public Object preference(@RequestBody PreferenceRequest r) {
        return service.preference(user().getId(), r);
    }

    @PostMapping("/api/product-events/batch")
    public Object events(@Valid @RequestBody EventsRequest r) {
        return Map.of("accepted", events.batch(user().getId(), r));
    }

    @GetMapping("/api/feedback")
    public Object feedback() {
        return service.feedback(user().getId());
    }

    @PostMapping("/api/feedback")
    public Object feedback(@Valid @RequestBody FeedbackRequest r) {
        return service.submit(user().getId(), r, false);
    }

    @PutMapping("/api/feedback")
    public Object supplement(@Valid @RequestBody FeedbackRequest r) {
        return service.submit(user().getId(), r, true);
    }

    @GetMapping("/api/admin/overview")
    public Object overview(@RequestParam(defaultValue = "7") int days) {
        admin();
        return queries.overview(days);
    }

    @GetMapping("/api/admin/users")
    public Object users(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        admin();
        return queries.users(q, page, size);
    }

    @GetMapping("/api/admin/users/{id}/activity")
    public Object activity(@PathVariable Long id, @RequestParam(defaultValue = "7") int days) {
        admin();
        return queries.activity(id, days);
    }

    @GetMapping("/api/admin/grants")
    public Object adminGrants(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        admin();
        return queries.grants(userId, page, size);
    }

    @PostMapping("/api/admin/grants")
    public Object grant(@Valid @RequestBody GrantRequest r) {
        return service.grant(admin().getId(), r);
    }

    @GetMapping("/api/admin/feedback")
    public Object feedbacks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        admin();
        return queries.feedbacks(status, page, size);
    }

    @PostMapping("/api/admin/feedback/{id}/review")
    public Object review(@PathVariable Long id, @Valid @RequestBody ReviewRequest r) {
        return service.review(admin().getId(), id, r);
    }

    @GetMapping("/api/admin/analytics")
    public Object analytics(@RequestParam(defaultValue = "7") int days) {
        admin();
        return queries.analytics(days);
    }
}
