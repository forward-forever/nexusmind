package com.wude.nexusmind.agent.evaluation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;

public class AgentBehaviorEvaluationCli implements ApplicationRunner {

    private final ConfigurableApplicationContext applicationContext;
    private final AgentBehaviorDatasetLoader datasetLoader;
    private final AgentBehaviorEvaluationService evaluationService;
    private final AgentBehaviorReportWriter reportWriter;

    public AgentBehaviorEvaluationCli(ConfigurableApplicationContext applicationContext,
                                      AgentBehaviorDatasetLoader datasetLoader,
                                      AgentBehaviorEvaluationService evaluationService,
                                      AgentBehaviorReportWriter reportWriter) {
        this.applicationContext = applicationContext;
        this.datasetLoader = datasetLoader;
        this.evaluationService = evaluationService;
        this.reportWriter = reportWriter;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            Path datasetPath = requiredPath(arguments, "dataset");
            Path outputPath = requiredPath(arguments, "output");
            long knowledgeBaseId = requiredPositiveLong(arguments, "knowledge-base-id");
            AgentBehaviorDataset dataset = datasetLoader.load(datasetPath);
            AgentBehaviorReport report = evaluationService.evaluate(dataset, knowledgeBaseId);
            AgentBehaviorReportFiles files = reportWriter.write(report, outputPath);
            System.out.println("Agent behavior evaluation completed.");
            System.out.println("JSON report: " + files.json());
            System.out.println("Markdown report: " + files.markdown());
        } finally {
            applicationContext.close();
        }
    }

    private static Path requiredPath(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Exactly one --" + name + "=/absolute/path is required");
        }
        return Path.of(values.get(0)).toAbsolutePath().normalize();
    }

    private static long requiredPositiveLong(ApplicationArguments arguments, String name) {
        List<String> values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1) {
            throw new IllegalArgumentException("Exactly one --" + name + "=<positive ID> is required");
        }
        try {
            long value = Long.parseLong(values.get(0));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " must be a positive integer", exception);
        }
    }
}
