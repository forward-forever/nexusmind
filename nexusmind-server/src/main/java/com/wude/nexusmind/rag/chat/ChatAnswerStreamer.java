package com.wude.nexusmind.rag.chat;

import reactor.core.publisher.Flux;

public interface ChatAnswerStreamer {

    Flux<String> stream(RagPrompt prompt);
}
