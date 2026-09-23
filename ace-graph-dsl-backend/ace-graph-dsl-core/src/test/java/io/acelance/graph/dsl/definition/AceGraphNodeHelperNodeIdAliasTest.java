package io.acelance.graph.dsl.definition;

import io.acelance.graph.dsl.bizparam.NodeBizParamInterpreterRegistry;
import io.acelance.graph.dsl.llm.MemoryMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AceGraphNodeHelperNodeIdAliasTest {

    @Test
    void nodeIdLookupKeys_stripsAgentPrefix() {
        assertEquals(
                List.of("agent:ls_biz_node", "ls_biz_node"),
                AceGraphNodeHelper.nodeIdLookupKeys("agent:ls_biz_node"));
    }

    @Test
    void findNode_resolvesRuntimeAgentPrefixedId() {
        GenericAgentSpec spec = new GenericAgentSpec(
                null, null, false, null, null,
                "user_query", "agent_result", "BIZ", null,
                false, List.of(), false, null,
                false, List.of(), false, List.of(), Map.of(),
                false, List.of(),
                MemoryMode.NONE, false,
                true, "lesso.sse-frame",
                "{\"thinking\":true,\"nodeDisplay\":\"业务处理\"}");
        NodeRef node = new NodeRef(
                "ls_biz_node", "GENERIC_AGENT", Map.of(), null, null,
                null, null, null, spec);
        GraphDefinition def = new GraphDefinition(
                "g1", "test", "1", null, Map.of(),
                List.of(node), List.of(), null, null);
        AceGraphNodeHelper helper = new AceGraphNodeHelper(
                id -> def, new NodeBizParamInterpreterRegistry(List.of()));

        Optional<NodeRef> hit = helper.findNode("g1", "agent:ls_biz_node");
        assertTrue(hit.isPresent());
        assertEquals("ls_biz_node", hit.get().nodeId());
        assertTrue(helper.isBizParamsEnabled("g1", "agent:ls_biz_node"));
        assertEquals("{\"thinking\":true,\"nodeDisplay\":\"业务处理\"}",
                helper.getBizParamRaw("g1", "agent:ls_biz_node"));
    }
}
