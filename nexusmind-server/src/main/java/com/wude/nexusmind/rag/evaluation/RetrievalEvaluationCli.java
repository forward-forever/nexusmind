package com.wude.nexusmind.rag.evaluation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import com.wude.nexusmind.rag.retrieval.RetrieverType;

import java.nio.file.Path;
import java.util.List;

public class RetrievalEvaluationCli implements ApplicationRunner {

    private final ConfigurableApplicationContext applicationContext;
    private final RetrievalDatasetLoader datasetLoader;
    private final RetrievalEvaluationService evaluationService;
    private final RetrievalReportWriter reportWriter;

    public RetrievalEvaluationCli(ConfigurableApplicationContext applicationContext,
                                  RetrievalDatasetLoader datasetLoader,
                                  RetrievalEvaluationService evaluationService,
                                  RetrievalReportWriter reportWriter) {
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
            RetrieverType retrieverType = requiredRetriever(arguments);
            RetrievalEvaluationDataset dataset = datasetLoader.load(datasetPath);
            RetrievalEvaluationReport report = evaluationService.evaluate(dataset, retrieverType);
            RetrievalReportFiles files = reportWriter.write(report, outputPath);
            System.out.println("Retrieval evaluation completed.");
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

    private static RetrieverType requiredRetriever(ApplicationArguments arguments) {
        List<String> values = arguments.getOptionValues("retriever");
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Exactly one --retriever=DENSE|BM25 is required");
        }
        try {
            return RetrieverType.valueOf(values.get(0).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported retriever: " + values.get(0), exception);
        }
    }
}
