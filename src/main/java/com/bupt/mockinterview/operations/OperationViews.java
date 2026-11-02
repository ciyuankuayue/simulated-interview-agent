package com.bupt.mockinterview.operations;

import java.math.BigDecimal;
import java.util.*;

final class OperationViews {
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }

    static BigDecimal points(long tokens) {
        return BigDecimal.valueOf(tokens).divide(BigDecimal.valueOf(500));
    }

    static Map<String, Object> grant(OperationGrant g, String username, boolean admin) {
        var m =
                map(
                        "id",
                        g.getId(),
                        "userId",
                        g.getUserId(),
                        "username",
                        username,
                        "points",
                        points(g.getGrantTokens()),
                        "type",
                        g.getType(),
                        "reason",
                        g.getReason(),
                        "amountCny",
                        g.getAmountCny(),
                        "createdAt",
                        g.getCreatedAt());
        if (admin)
            m.putAll(
                    map(
                            "operatorId",
                            g.getOperatorId(),
                            "requestId",
                            g.getRequestId(),
                            "orderRef",
                            g.getOrderRef()));
        return m;
    }

    static Map<String, Object> feedback(ProductFeedback f, String username, boolean admin) {
        var m =
                map(
                        "id",
                        f.getId(),
                        "progress",
                        f.getProgress(),
                        "experience",
                        f.getExperience(),
                        "blockers",
                        f.getBlockers().isEmpty() ? List.of() : List.of(f.getBlockers().split(",")),
                        "suggestions",
                        f.getSuggestions(),
                        "followUp",
                        f.isFollowUp(),
                        "contact",
                        f.getContact(),
                        "status",
                        f.getStatus(),
                        "reviewNote",
                        f.getReviewNote(),
                        "createdAt",
                        f.getCreatedAt(),
                        "updatedAt",
                        f.getUpdatedAt());
        if (admin)
            m.putAll(
                    map(
                            "userId",
                            f.getUserId(),
                            "username",
                            username,
                            "originalExperience",
                            f.getOriginalExperience()));
        return m;
    }

    static Map<String, Object> page(List<?> items, int page, int size, long total) {
        return map("items", items, "page", page, "size", size, "total", total);
    }

    static int size(int n) {
        if (n < 1 || n > 100) throw new IllegalArgumentException("size应为1至100");
        return n;
    }

    static int page(int n) {
        if (n < 0 || n > 100000) throw new IllegalArgumentException("page超出范围");
        return n;
    }
}
