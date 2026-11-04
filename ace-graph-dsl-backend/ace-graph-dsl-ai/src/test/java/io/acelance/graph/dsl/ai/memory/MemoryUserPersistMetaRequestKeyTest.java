package io.acelance.graph.dsl.ai.memory;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.acelance.graph.dsl.llm.LlmRequestContext;
import io.acelance.graph.dsl.media.MediaRef;
import io.acelance.graph.dsl.media.MediaRefs;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落盘与运行时 media 键分离：仅 requestMediaRefs 进入 extras 拆分。
 */
class MemoryUserPersistMetaRequestKeyTest {

    @Test
    void splitUrls_fromRequestKey_ignoresHydratedRuntimeMedia() {
        String hist = "https://moss.example/history.png";
        String turn = "https://moss.example/turn-only.png";
        Map<String, Object> data = new HashMap<>();
        data.put("media_refs", List.of(
                new MediaRef(hist, "image/png", null, "image"),
                new MediaRef(turn, "image/png", null, "image")));
        data.put(LlmRequestContext.ACE_REQUEST_MEDIA_REFS_KEY, List.of(
                new MediaRef(turn, "image/png", null, "image")));
        OverAllState state = new OverAllState(data);

        List<MediaRef> runtime = MediaRefs.readFrom(state, "media_refs");
        List<MediaRef> request = MediaRefs.readFrom(state, LlmRequestContext.ACE_REQUEST_MEDIA_REFS_KEY);
        MemoryUserPersistMeta.SplitUrls persist = MemoryUserPersistMeta.splitUrls(request);

        assertEquals(2, runtime.size());
        assertEquals(1, request.size());
        assertEquals(List.of(turn), persist.images());
        assertTrue(persist.files().isEmpty());
    }

    @Test
    void splitUrls_emptyWhenOnlyHydratedMediaRefs() {
        Map<String, Object> data = new HashMap<>();
        data.put("media_refs", List.of(
                new MediaRef("https://moss.example/a.pdf", "application/pdf", null, "file")));
        OverAllState state = new OverAllState(data);

        List<MediaRef> request = MediaRefs.readFrom(state, LlmRequestContext.ACE_REQUEST_MEDIA_REFS_KEY);
        MemoryUserPersistMeta.SplitUrls persist = MemoryUserPersistMeta.splitUrls(request);

        assertTrue(request.isEmpty());
        assertTrue(persist.images().isEmpty());
        assertTrue(persist.files().isEmpty());
        assertEquals(1, MediaRefs.readFrom(state, "media_refs").size());
    }
}
