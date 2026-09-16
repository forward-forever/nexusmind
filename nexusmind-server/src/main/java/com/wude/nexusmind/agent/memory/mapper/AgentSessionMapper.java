package com.wude.nexusmind.agent.memory.mapper;

import com.wude.nexusmind.agent.memory.domain.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface AgentSessionMapper {

    int insert(AgentSessionEntity session);

    Optional<AgentSessionEntity> findById(String sessionId);

    int touch(String sessionId);
}
