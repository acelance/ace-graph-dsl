package io.acelance.graph.dsl.resource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceItem} 三级树结构单测。
 */
class ResourceItemTest {

    @Test
    void mcpServerWithToolChildren() {
        ResourceItem server = ResourceItem.mcpServer(
                "crm", "CRM", "crm mcp",
                List.of(ResourceItem.tool("query_order", "查订单"),
                        ResourceItem.tool("delete_order", "删订单")));
        assertEquals("crm", server.key());
        assertEquals(2, server.children().size());
        assertEquals("query_order", server.children().get(0).key());
        assertTrue(server.children().get(0).children().isEmpty());
    }

    @Test
    void threeArgCtorHasEmptyChildren() {
        ResourceItem flat = new ResourceItem("p1", "Prompt1", "d");
        assertTrue(flat.children().isEmpty());
    }
}
