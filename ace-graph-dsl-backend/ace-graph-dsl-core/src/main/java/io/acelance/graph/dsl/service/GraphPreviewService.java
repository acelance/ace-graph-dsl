package io.acelance.graph.dsl.service;

import io.acelance.graph.dsl.builder.DynamicGraphBuilder;
import io.acelance.graph.dsl.definition.GraphDefinition;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import org.springframework.stereotype.Service;

/**
 * 图预览服务：将 DSL 编译为 StateGraph 后导出 PlantUML / Mermaid。
 */
@Service
public class GraphPreviewService {

    private final DynamicGraphBuilder builder;

    public GraphPreviewService(DynamicGraphBuilder builder) {
        this.builder = builder;
    }

    /**
     * 导出 PlantUML。
     */
    public String toPlantUml(GraphDefinition def) throws GraphStateException {
        StateGraph stateGraph = builder.buildStateGraph(def);
        return stateGraph.getGraph(GraphRepresentation.Type.PLANTUML, def.graphId()).content();
    }

    /**
     * 导出 Mermaid。
     */
    public String toMermaid(GraphDefinition def) throws GraphStateException {
        StateGraph stateGraph = builder.buildStateGraph(def);
        return stateGraph.getGraph(GraphRepresentation.Type.MERMAID, def.graphId()).content();
    }
}
