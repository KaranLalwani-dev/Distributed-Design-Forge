package com.karandev.distributed_design_forge.intelligence_service.dto.chat;

import com.karandev.distributed_design_forge.common_lib.enums.MessageRole;

import java.time.Instant;
import java.util.List;

public record ChatResponse(
        Long id,
        MessageRole role,
        List<ChatEventResponse> events,
        String content,
        Integer tokensUsed,
        Instant createdAt

) {
}
