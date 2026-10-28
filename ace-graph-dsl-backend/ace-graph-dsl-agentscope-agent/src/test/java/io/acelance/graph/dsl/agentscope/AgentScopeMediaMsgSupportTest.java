package io.acelance.graph.dsl.agentscope;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.ai.media.DefaultMediaRefResolver;
import io.acelance.graph.dsl.ai.media.MediaRefResolver;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * media_refs → AS Msg：图 ImageBlock，xlsx/pdf 材料注记。
 */
class AgentScopeMediaMsgSupportTest {

    @Test
    void emptyMediaRefsKeepsPlainText() {
        OverAllState state = new OverAllState(new HashMap<>());
        LlmRequestContext ctx = AgentScopeMediaMsgSupport.mediaContext(
                "a", "g", "n", "r", state, null);
        Msg msg = AgentScopeMediaMsgSupport.buildUserMsg("你好", state, null, null, ctx);
        assertEquals("你好", msg.getTextContent());
        assertEquals(1, msg.getContent().size());
        assertInstanceOf(TextBlock.class, msg.getContent().get(0));
    }

    @Test
    void imageGoesToImageBlockXlsxPdfToMaterialNotes() {
        Map<String, Object> data = new HashMap<>();
        data.put("media_refs", List.of(
                new MediaRef("https://cdn.example.com/a.png", "image/png", null, null),
                new MediaRef("https://cdn.example.com/report.xlsx", null, null, "file"),
                new MediaRef("https://cdn.example.com/doc.pdf", null, null, null)
        ));
        OverAllState state = new OverAllState(data);
        LlmRequestContext ctx = AgentScopeMediaMsgSupport.mediaContext(
                "a", "g", "n1", "r", state, null);

        Msg msg = AgentScopeMediaMsgSupport.buildUserMsg(
                "请分析附件", state, null, new DefaultMediaRefResolver(), ctx);

        List<ContentBlock> blocks = msg.getContent();
        assertTrue(blocks.size() >= 2, "应有 TextBlock + ImageBlock");
        assertInstanceOf(TextBlock.class, blocks.get(0));
        String text = ((TextBlock) blocks.get(0)).getText();
        assertTrue(text.startsWith("请分析附件"));
        assertTrue(text.contains("report.xlsx"), "xlsx 材料注记应进文本");
        assertTrue(text.contains("doc.pdf"), "pdf 材料注记应进文本");

        long images = blocks.stream().filter(ImageBlock.class::isInstance).count();
        assertEquals(1, images);
        ImageBlock image = (ImageBlock) blocks.stream()
                .filter(ImageBlock.class::isInstance).findFirst().orElseThrow();
        assertInstanceOf(URLSource.class, image.getSource());
        assertEquals("https://cdn.example.com/a.png", ((URLSource) image.getSource()).getUrl());
    }

    @Test
    void customMediaInputKeyIsHonored() {
        Map<String, Object> data = new HashMap<>();
        data.put("attachments", List.of(
                new MediaRef("https://cdn.example.com/b.jpg", "image/jpeg", null, "image")
        ));
        OverAllState state = new OverAllState(data);
        LlmRequestContext ctx = AgentScopeMediaMsgSupport.mediaContext(
                "a", "g", "n", "r", state, null);

        // Stub：避免 Default 路径，直接验证 Msg 组装
        MediaRefResolver stub = (c, refs) -> new MediaRefResolver.ResolveResult(
                List.of(Media.builder()
                        .mimeType(MimeTypeUtils.IMAGE_JPEG)
                        .data(URI.create(refs.get(0).url()))
                        .build()),
                List.of(),
                List.of());

        Msg msg = AgentScopeMediaMsgSupport.buildUserMsg(
                "hi", state, "attachments", stub, ctx);
        assertEquals(2, msg.getContent().size());
        assertInstanceOf(ImageBlock.class, msg.getContent().get(1));
    }

    @Test
    void effectiveMediaKeyFallsBackToMediaRefs() {
        assertEquals("media_refs", AgentScopeMediaMsgSupport.effectiveMediaKey(null));
        assertEquals("media_refs", AgentScopeMediaMsgSupport.effectiveMediaKey("  "));
        assertEquals("attachments", AgentScopeMediaMsgSupport.effectiveMediaKey("attachments"));
    }
}
