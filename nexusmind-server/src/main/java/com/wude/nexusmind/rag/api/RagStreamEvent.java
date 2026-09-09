package com.wude.nexusmind.rag.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wude.nexusmind.rag.context.RagSource;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagStreamEvent(
        String type,
        List<RagSourceResponse> sources,
        String content,
        String model,
        Long elapsedMs,
        String code,
        String message
) {

    public static RagStreamEvent sources(List<RagSource> sources) {
        return new RagStreamEvent(
                "sources",
                sources.stream().map(RagSourceResponse::from).toList(),
                null, null, null, null, null);
    }

    public static RagStreamEvent delta(String content) {
        return new RagStreamEvent("delta", null, content, null, null, null, null);
    }

    public static RagStreamEvent done(String model, long elapsedMs) {
        return new RagStreamEvent("done", null, null, model, elapsedMs, null, null);
    }

    public static RagStreamEvent error(String code, String message) {
        return new RagStreamEvent("error", null, null, null, null, code, message);
    }
}
