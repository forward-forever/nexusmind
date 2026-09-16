package com.wude.nexusmind.agent.memory.mapper;

import com.wude.nexusmind.agent.memory.domain.AgentMessageEntity;
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
