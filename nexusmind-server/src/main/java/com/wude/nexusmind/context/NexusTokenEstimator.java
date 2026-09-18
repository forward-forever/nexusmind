package com.wude.nexusmind.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Local, conservative token estimation. Values are estimates, not provider tokenizer output.
 */
public interface NexusTokenEstimator {

    int estimate(String text);

    int estimateMessages(List<Message> messages);
}
