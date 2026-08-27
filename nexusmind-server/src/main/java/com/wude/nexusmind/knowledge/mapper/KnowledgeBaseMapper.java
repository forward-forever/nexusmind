package com.wude.nexusmind.knowledge.mapper;

import com.wude.nexusmind.knowledge.domain.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findById(long id);

    List<KnowledgeBase> findAll();

    int update(KnowledgeBase knowledgeBase);
}
