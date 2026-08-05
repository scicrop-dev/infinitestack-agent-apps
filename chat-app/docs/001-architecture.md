# Arquitetura

```
Adapters  ──►  ConversationService  ──►  WorkflowEngine  ──►  Actions  ──►  Adapters
                       │                       ▲
                       ▼                       │
                  Repositories  ───────────────┘
                  (Postgres do host)
```

## Camadas

| Camada | Classes | Responsabilidade |
|---|---|---|
| **Adapter** | `ChatController` | Traduz o protocolo do canal em eventos do motor. Hoje só o chat de teste do painel; um adapter externo usa exatamente os mesmos endpoints. |
| **Orquestração** | `ConversationService` | Carrega fluxo e estado, chama o motor, grava o resultado e o histórico. Única camada que conhece as duas pontas. |
| **Motor** | `WorkflowEngine`, `ConditionEvaluator`, `VariableInterpolator` | Percorre o grafo. Sem banco, sem HTTP, sem Spring além da injeção de um `int`. |
| **Definição** | `WorkflowParser`, `WorkflowValidator`, `WorkflowService` | Converte JSON em domínio e recusa fluxo quebrado antes de gravar. |
| **Persistência** | `WorkflowRepository`, `ConversationRepository`, `SchemaInitializer` | `JdbcTemplate` sobre o datasource herdado do host via `IS_DATASOURCE_*`. |
| **Painel** | `AgentPanelController`, `isp-index.html` | Uma tela: lista de fluxos, editor JSON validado e chat de teste. |
| **Runtime IS** | `PluginRuntimeController`, `PluginWebConfig`, `PluginDataSourceConfig` | Contrato do `.ispz`: health, assets e datasource. |

## Decisões estruturais

**O motor é uma função pura.** `advance(workflow, state, event) → (novo state, ações)`. Não escreve
em banco nem envia mensagem. É o que permite testar um fluxo inteiro — inclusive ciclos e erros — em
milissegundos, e é o que torna um adapter novo uma questão de tradução de protocolo, não de lógica.

**Estado e identidade são coisas separadas.** `ConversationState` (status, nó atual, variáveis) é o
que o motor lê e reescreve; `Conversation` acrescenta id, canal e timestamps. O motor nunca toca em
identidade.

**A definição é gravada como texto JSON.** Não há tabela de nós. O formato ainda vai crescer, e uma
modelagem relacional exigiria migração a cada tipo de nó novo.

**Health não toca o banco.** O IS conclui a instalação quando `/api/runtime-health` responde; se ele
dependesse do datasource do cliente, um banco fora do ar reprovaria um pacote válido. O schema é
preparado em thread daemon (`StartupBootstrap`) e retentado a cada escrita.
