package com.wude.nexusmind.agent.memory.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentMessageMapper {

    int insert(AgentMessageEntity message);

    List<AgentMessageEntity> findRecentBySessionId(
            @Param("sessionId") String sessionId,
            @Param("limit") int limit);
}
