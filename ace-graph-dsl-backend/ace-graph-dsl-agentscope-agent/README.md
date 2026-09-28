# ace-graph-dsl-agentscope-agent

可选模块（**M3 / Q4**）：`impl=AGENTSCOPE` 子 Agent，优先官方
`spring-ai-alibaba-starter-agentscope` 包装为 FlowAgent `subAgent`。

## 能力

| 组件 | 说明 |
|------|------|
| `AgentScopeSubAgentResolver` | `ref=agentscope:{id}` → `AgentScopeAgent` |
| `DefaultAgentScopeModelFactory` | ACE Spec / modelConfigKey → AgentScope `OpenAIChatModel` |
| `AceGraphDslAgentscopeAgentAutoConfiguration` | 注册 Resolver / ModelFactory |

## 宿主依赖

```xml
<!-- 高阶节点本体 -->
<dependency>
  <groupId>io.acelance</groupId>
  <artifactId>ace-graph-dsl-saa-agent</artifactId>
</dependency>
<!-- AgentScope 子 Agent（本模块，不强制） -->
<dependency>
  <groupId>io.acelance</groupId>
  <artifactId>ace-graph-dsl-agentscope-agent</artifactId>
</dependency>
```

未引入本模块时：`impl=AGENTSCOPE` → 校验报「无匹配 SubAgentResolver」。

## 测试

```bash
mvn -pl ace-graph-dsl-agentscope-agent -am "-Dtest=AgentScopeSequentialFactoryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## 说明

- id 仍指向 ACE 注册 GenericAgent；**不**另建 AgentScope 资源目录
- 子记忆强制 NONE（Q5）
- starter **不**传递本模块
