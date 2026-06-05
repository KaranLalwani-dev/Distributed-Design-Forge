package com.karandev.distributed_design_forge.intelligence_service.mapper;

import com.karandev.distributed_design_forge.intelligence_service.dto.chat.ChatResponse;
import com.karandev.distributed_design_forge.intelligence_service.entity.ChatMessage;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ChatMapper {

    List<ChatResponse> fromListOfChatMessage(List<ChatMessage> chatMessageList);
}