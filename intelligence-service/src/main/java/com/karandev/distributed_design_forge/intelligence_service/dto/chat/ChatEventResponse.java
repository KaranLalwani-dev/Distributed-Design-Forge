package com.karandev.distributed_design_forge.intelligence_service.dto.chat;

import com.karandev.distributed_design_forge.common_lib.enums.ChatEventType;

public record ChatEventResponse(
        Long id,
        ChatEventType type,
        Integer sequenceOrder,
        String content,
        String filePath,
        String metadata
) {
}
