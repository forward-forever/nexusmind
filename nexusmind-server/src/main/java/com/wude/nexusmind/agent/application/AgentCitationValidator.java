package com.wude.nexusmind.agent.application;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates run-scoped knowledge citations after the streamed answer is complete. */
public final class AgentCitationValidator {

    private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)]");

    public Set<String> invalidCitations(String answer, AgentSourceRegistry registry) {
        Set<String> invalid = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            String sourceId = "S" + matcher.group(1);
            if (registry.resolveSource(sourceId).isEmpty()) {
                invalid.add(sourceId);
            }
        }
        return Set.copyOf(invalid);
    }
}
