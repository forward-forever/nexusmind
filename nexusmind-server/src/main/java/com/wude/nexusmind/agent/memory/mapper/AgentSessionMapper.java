package com.wude.nexusmind.agent.memory.mapper;

import com.wude.nexusmind.agent.memory.domain.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface AgentSessionMapper {

    int insert(AgentSessionEntity session);

    int insertWithLease(@Param("sessionId") String sessionId,
                        @Param("knowledgeBaseId") long knowledgeBaseId,
                        @Param("runId") String runId,
                        @Param("leaseMicros") long leaseMicros);

    Optional<AgentSessionEntity> findById(String sessionId);

    Optional<AgentSessionEntity> findOwnedByIdForUpdate(@Param("sessionId") String sessionId,
                                                        @Param("runId") String runId);

    int acquireLease(@Param("sessionId") String sessionId,
                     @Param("knowledgeBaseId") long knowledgeBaseId,
                     @Param("runId") String runId,
                     @Param("leaseMicros") long leaseMicros);

    int releaseLease(@Param("sessionId") String sessionId,
                     @Param("runId") String runId);

    int touch(String sessionId);

    int touchOwned(@Param("sessionId") String sessionId,
                   @Param("runId") String runId);
}
