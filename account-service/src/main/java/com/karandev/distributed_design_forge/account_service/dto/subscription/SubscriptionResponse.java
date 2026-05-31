package com.karandev.distributed_design_forge.account_service.dto.subscription;

import java.time.Instant;

public record SubscriptionResponse(
        PlanResponse plan,
        String status,
        Instant currentPeriodEnd,
        Long tokensUsedThisCycle
) {
}
