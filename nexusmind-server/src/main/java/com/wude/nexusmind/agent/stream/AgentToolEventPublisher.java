package com.wude.nexusmind.agent.stream;

@FunctionalInterface
public interface AgentToolEventPublisher {

    void publish(AgentStreamEvent event);
}
