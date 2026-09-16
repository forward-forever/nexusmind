package com.wude.nexusmind.agent.memory;

import java.util.regex.Pattern;

public final class HistoricalCitationSanitizer {

    private static final Pattern AGENT_CITATION = Pattern.compile("\\[S[1-9]\\d*\\]");

    public String sanitize(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        return AGENT_CITATION.matcher(content).replaceAll("");
    }
}
