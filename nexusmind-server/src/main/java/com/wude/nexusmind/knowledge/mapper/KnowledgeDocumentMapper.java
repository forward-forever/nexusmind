package com.wude.nexusmind.knowledge.mapper;

import com.wude.nexusmind.knowledge.domain.DocumentStatus;
import com.wude.nexusmind.knowledge.domain.DocumentIndexStatus;
import com.wude.nexusmind.knowledge.domain.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeDocumentMapper {

    int insert(KnowledgeDocument document);

    Optional<KnowledgeDocument> findById(long id);

    Optional<KnowledgeDocument> findByIdForUpdate(long id);

    List<KnowledgeDocument> findByKnowledgeBaseId(long knowledgeBaseId);

    List<KnowledgeDocument> findByIds(@Param("ids") Collection<Long> ids);

    Optional<KnowledgeDocument> findByKnowledgeBaseIdAndSha256(
            @Param("knowledgeBaseId") long knowledgeBaseId,
            @Param("fileSha256") String fileSha256);

    int updateStatus(@Param("id") long id,
                     @Param("status") DocumentStatus status,
                     @Param("chunkCount") int chunkCount,
                     @Param("errorMessage") String errorMessage);

    int updateIndexStatus(@Param("id") long id,
                          @Param("indexStatus") DocumentIndexStatus indexStatus,
                          @Param("indexErrorMessage") String indexErrorMessage);

    int resetIndexStatusByKnowledgeBaseId(long knowledgeBaseId);
}
