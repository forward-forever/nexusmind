package com.wude.nexusmind.rag.evaluation;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;

public class BenchmarkDatasetResolverCli implements ApplicationRunner {

    private final ConfigurableApplicationContext applicationContext;
    private final BenchmarkSourceDatasetLoader sourceLoader;
    private final BenchmarkDatasetResolverService resolverService;
    private final BenchmarkGoldenDatasetWriter goldenDatasetWriter;

    public BenchmarkDatasetResolverCli(ConfigurableApplicationContext applicationContext,
                                       BenchmarkSourceDatasetLoader sourceLoader,
                                       BenchmarkDatasetResolverService resolverService,
                                       BenchmarkGoldenDatasetWriter goldenDatasetWriter) {
        this.applicationContext = applicationContext;
        this.sourceLoader = sourceLoader;
        this.resolverService = resolverService;
        this.goldenDatasetWriter = goldenDatasetWriter;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            long documentId = requiredPositiveLong(arguments, "document-id");
            Path source = requiredPath(arguments, "source");
            Path output = requiredPath(arguments, "output");
            if (source.equals(output)) {
                throw new IllegalArgumentException("--source and --output must be different files");
            }

            List<BenchmarkSourceCase> sourceCases = sourceLoader.load(source);
            BenchmarkDatasetResolution resolution = resolverService.resolve(documentId, sourceCases);
            Path writtenOutput = goldenDatasetWriter.write(resolution.cases(), output);

            System.out.println("Benchmark dataset resolution completed.");
            System.out.println("Document: " + resolution.documentId());
            System.out.println("KnowledgeBase: " + resolution.knowledgeBaseId());
            System.out.println("Questions: " + resolution.cases().size());
            System.out.println("Referenced pages: " + resolution.referencedPageCount());
            System.out.println("Resolved chunks: " + resolution.resolvedChunkCount());
            for (BenchmarkDatasetResolution.QueryMapping mapping : resolution.mappings()) {
                System.out.println(mapping.queryId() + " page" + mapping.expectedPages()
                        + " -> chunk" + mapping.relevantChunkIds());
            }
            System.out.println("Output: " + writtenOutput);
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
        if (values == null || values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("Exactly one --" + name + "=<positive ID> is required");
        }
        try {
            long value = Long.parseLong(values.get(0));
            if (value <= 0) {
                throw new IllegalArgumentException("--" + name + " must be positive");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " must be a positive integer", exception);
        }
    }
}
