package com.wude.nexusmind.agent.model;

public record DocumentContextItem(
        String sourceId,
        String fileName,
        Integer pageNo,
        String sectionTitle,
        String content,
        boolean isTarget) {
}
