package com.wude.nexusmind.knowledge.mapper;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface KnowledgeChunkMapper {

    int batchInsert(@Param("chunks") List<KnowledgeChunk> chunks);

    List<KnowledgeChunk> findByDocumentId(long documentId);

    List<KnowledgeChunk> findByIds(@Param("ids") Collection<Long> ids);

    int deleteByDocumentId(long documentId);
}
