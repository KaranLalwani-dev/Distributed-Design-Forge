package com.karandev.distributed_design_forge.intelligence_service.service;

import com.karandev.distributed_design_forge.intelligence_service.dto.chat.ChatResponse;

import java.util.List;

public interface ChatService {

    List<ChatResponse> getProjectChatHistory(Long projectId);
}
