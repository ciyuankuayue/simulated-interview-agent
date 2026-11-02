package com.bupt.mockinterview.operations;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public final class OperationDtos {
    private OperationDtos() {}

    public record GrantRequest(
            @NotBlank @Size(max = 32) String username,
            @NotNull @DecimalMin("0.002") @DecimalMax("1000000") BigDecimal points,
            @NotBlank String type,
            @NotBlank @Size(max = 500) String reason,
            BigDecimal amountCny,
            @Size(max = 120) String orderRef,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{8,100}") String requestId) {}

    public record FeedbackRequest(
            @NotBlank String progress,
            @NotBlank @Size(max = 2000) String experience,
            @NotEmpty @Size(max = 10) List<String> blockers,
            @Size(max = 2000) String suggestions,
            boolean followUp,
            @Size(max = 200) String contact) {}

    public record ReviewRequest(@NotBlank String action, @Size(max = 500) String note) {}

    public record PreferenceRequest(Boolean enabled, Boolean reportInvitationSeen) {}

    public record EventInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{8,120}") String eventId,
            @NotBlank String name,
            String page,
            Long sessionId,
            Long durationMs,
            String source,
            String device) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("不允许的事件字段");
        }
    }

    public record EventsRequest(
            @NotNull @Size(max = 20) List<@NotNull @jakarta.validation.Valid EventInput> events) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("不允许的事件字段");
        }
    }
}
