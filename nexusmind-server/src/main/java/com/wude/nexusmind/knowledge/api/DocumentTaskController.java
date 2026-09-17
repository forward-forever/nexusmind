package com.wude.nexusmind.knowledge.api;

import com.wude.nexusmind.knowledge.task.DocumentTaskEnqueueResult;
import com.wude.nexusmind.knowledge.task.DocumentTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "nexusmind.document-task.enabled", havingValue = "true")
public class DocumentTaskController {

    private final DocumentTaskService taskService;

    public DocumentTaskController(DocumentTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/documents/{documentId}/process")
    public ResponseEntity<DocumentTaskResponse> process(@PathVariable long documentId) {
        return enqueue(taskService.enqueueProcess(documentId));
    }

    @PostMapping("/documents/{documentId}/index")
    public ResponseEntity<DocumentTaskResponse> index(@PathVariable long documentId) {
        return enqueue(taskService.enqueueIndex(documentId));
    }

    @GetMapping("/document-tasks/{taskId}")
    public DocumentTaskResponse get(@PathVariable long taskId) {
        return DocumentTaskResponse.from(taskService.get(taskId));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/document-tasks")
    public List<DocumentTaskResponse> list(@PathVariable long knowledgeBaseId,
                                           @RequestParam(defaultValue = "true") boolean active) {
        if (!active) throw new IllegalArgumentException("Only active document task listing is supported");
        return taskService.listActive(knowledgeBaseId).stream().map(DocumentTaskResponse::from).toList();
    }

    private static ResponseEntity<DocumentTaskResponse> enqueue(DocumentTaskEnqueueResult result) {
        HttpStatus status = result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(DocumentTaskResponse.from(result.task()));
    }
}
