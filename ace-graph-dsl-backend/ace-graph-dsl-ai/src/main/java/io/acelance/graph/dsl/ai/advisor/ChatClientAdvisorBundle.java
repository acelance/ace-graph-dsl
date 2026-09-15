package io.acelance.graph.dsl.ai.advisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务 Advisor 与请求级 params（P3.8）。
 *
 * <p>{@code advisorParams} 会与框架写入的 {@code ChatMemory.CONVERSATION_ID} 合并后
 * 交给 {@code ChatClient.advisors(a -> a.params(...))}。</p>
 */
public record ChatClientAdvisorBundle(
        List<Advisor> advisors,
        Map<String, Object> advisorParams
) {
    public ChatClientAdvisorBundle {
        advisors = advisors == null ? List.of() : List.copyOf(advisors);
        advisorParams = advisorParams == null ? Map.of() : Map.copyOf(advisorParams);
    }

    public static ChatClientAdvisorBundle empty() {
        return new ChatClientAdvisorBundle(List.of(), Map.of());
    }

    public boolean isEmpty() {
        return advisors.isEmpty() && advisorParams.isEmpty();
    }

    public static ChatClientAdvisorBundle of(List<Advisor> advisors) {
        return new ChatClientAdvisorBundle(Objects.requireNonNullElse(advisors, List.of()), Map.of());
    }
}
