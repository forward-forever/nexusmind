package com.wude.nexusmind.rag.index;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

public class KnowledgeBaseIndexRebuildCli implements ApplicationRunner {

    static final String CONFIRMATION = "DROP_AND_REBUILD";

    private final ConfigurableApplicationContext applicationContext;
    private final KnowledgeBaseIndexRebuildService rebuildService;

    public KnowledgeBaseIndexRebuildCli(ConfigurableApplicationContext applicationContext,
                                        KnowledgeBaseIndexRebuildService rebuildService) {
        this.applicationContext = applicationContext;
        this.rebuildService = rebuildService;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            requireConfirmation(arguments);
            long knowledgeBaseId = requiredKnowledgeBaseId(arguments);
            KnowledgeBaseIndexRebuildReport report = rebuildService.rebuild(knowledgeBaseId);
            System.out.printf("Knowledge base rebuild completed: knowledgeBaseId=%d, total=%d, indexed=%d, failed=%d%n",
                    report.knowledgeBaseId(), report.total(), report.indexed(), report.failed());
            for (KnowledgeBaseIndexRebuildReport.Failure failure : report.failures()) {
                System.err.printf("Document rebuild failed: documentId=%d, message=%s%n",
                        failure.documentId(), failure.message());
            }
            if (report.failed() > 0) {
                throw new IllegalStateException("Knowledge base rebuild completed with failed documents");
            }
        } finally {
            applicationContext.close();
        }
    }

    private static void requireConfirmation(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues("confirm");
        if (values == null || values.size() != 1 || !CONFIRMATION.equals(values.get(0))) {
            throw new IllegalArgumentException(
                    "Destructive rebuild requires --confirm=" + CONFIRMATION);
        }
    }

    private static long requiredKnowledgeBaseId(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues("knowledge-base-id");
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Exactly one --knowledge-base-id=<positive id> is required");
        }
        try {
            long value = Long.parseLong(values.get(0));
            if (value <= 0) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("knowledge-base-id must be a positive integer", exception);
        }
    }
}
