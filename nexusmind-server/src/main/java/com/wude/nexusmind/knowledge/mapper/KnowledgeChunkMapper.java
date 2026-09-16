package com.wude.nexusmind.knowledge.mapper;

import com.wude.nexusmind.knowledge.domain.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeChunkMapper {

    int batchInsert(@Param("chunks") List<KnowledgeChunk> chunks);

    List<KnowledgeChunk> findByDocumentId(long documentId);

    Optional<KnowledgeChunk> findById(long id);

    List<KnowledgeChunk> findByDocumentIdAndChunkIndexBetween(
            @Param("documentId") long documentId,
            @Param("fromIndex") int fromIndex,
            @Param("toIndex") int toIndex);

    List<KnowledgeChunk> findByIds(@Param("ids") Collection<Long> ids);

    int deleteByDocumentId(long documentId);
}
