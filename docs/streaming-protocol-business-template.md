# 业务流式协议接入模板（§9.0 / P1.3）

> 框架**不定义** SSE 字段协议。本文是业务项目自建协议时的接入清单与样例骨架。  
> 权威定案见：`docs/streaming-llm-node-template-design.md` §9.0 / §9.6 / §9.6.7。

---

## 1. 职责划分（必读）

| 职责 | 归属 |
|---|---|
| SSE 每帧字段名 / 层级 / 编码 | **业务** |
| 挂载入口 `StreamingChunkFormatter` | 框架 |
| `streamResponseKind` / `nodeId` / `isLast` 等事实标签 | 框架透传（Java 侧） |
| 设计器调试所见格式 | **框架** `/debug/stream`（与生产无关） |

**禁止**：引导生产终端用户调用 `/execution/{graphId}/debug/stream`。  
该端点需 `graph:validate`，仅供编排人员调试。

---

## 2. 生产端点 vs 调试端点

| 端点 | 谁用 | Formatter |
|---|---|---|
| `POST .../execution/{graphId}/stream` | 生产业务 | 业务 `@Bean StreamingChunkFormatter`（或默认原生） |
| `POST .../execution/{graphId}/debug/stream` | ace-graph-dsl-ui | **强制** `DebugStreamingChunkFormatter`，忽略业务 Bean |

调试面板文案应明示：「调试所见 ≠ 生产所下发」。

---

## 3. 最小接入步骤

1. 开启 Web / execution（见 `STREAMING_OUTPUT.md`）。
2. 实现 `@Bean StreamingChunkFormatter`，在 `format(StreamingContext)` 内读：
   - `ctx.getTokenChunk()` / `ctx.getNodeId()` / `ctx.isLast()`
   - `ctx.getResponseKind()`（节点配置的 kind KEY，可空）
3. （可选）继承 `KindDispatchingChunkFormatter`，按 BIZ / OUTPUT / 其它分派。
4. **不要**在框架里写死后端字段示例当作线上协议。

### 样例骨架（业务工程）

```java
@Component
public class MyBizStreamingChunkFormatter extends KindDispatchingChunkFormatter {

    @Override
    protected Object onBiz(StreamingContext ctx) {
        // TODO: 按贵司过程流协议组装 Map/DTO
        return Map.of("channel", "biz", "node", ctx.getNodeId(), "text", textOf(ctx));
    }

    @Override
    protected Object onOutput(StreamingContext ctx) {
        return Map.of("channel", "output", "node", ctx.getNodeId(), "text", textOf(ctx),
                "isEnd", ctx.isLast());
    }

    @Override
    protected Object onOther(StreamingContext ctx, String kind) {
        return Map.of("channel", kind == null ? "other" : kind, "node", ctx.getNodeId(),
                "text", textOf(ctx));
    }

    private static String textOf(StreamingContext ctx) {
        var tc = ctx.getTokenChunk();
        return tc != null ? tc.token() : "";
    }
}
```

字段名以上仅为示意，**请替换为业务真实协议**，不要原样上线。

---

## 4. 入口保留键（与流式并存）

| 保留键 | 含义 |
|---|---|
| `ace.graph.dsl.agentCode` | 智能体产品编码 |
| `ace.graph.dsl.forceSkills` | 强制激活 skill key 列表 |
| `ace.graph.dsl.runId` | 运行 ID（若入口自行写入） |

业务 Controller 解析用户口令后写入 `forceSkills`，框架不解析聊天原文。

---

## 5. 验收清单

- [ ] 生产 `/stream` 输出形状仅由业务 Formatter 决定  
- [ ] UI「调试流式」按钮仅在 `graph:validate` 可见，且只打 `/debug/stream`  
- [ ] 文档/培训材料不引导普通用户使用 debug 端点  
- [ ] kind 下拉来自 `GET /api/stream-response-kinds`，与运行期 KEY 一致  
