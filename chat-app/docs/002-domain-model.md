# Modelo de domínio

| Entidade | Classe | O que é |
|---|---|---|
| **Workflow** | `domain/Workflow` | Definição do fluxo: id, nome, nó inicial e o grafo de nós. Imutável, com índice `id→nó` montado na construção. |
| **Node** | `domain/Node` | Um passo: id, tipo, `next` e um `config` como mapa de strings. |
| **Conversation** | `domain/Conversation` | Uma execução do fluxo para um interlocutor: identidade, canal e estado. |
| **ConversationState** | `domain/ConversationState` | O que o motor manipula: status, nó atual e variáveis. |
| **Event** | `domain/Event` | O que entra: `START` ou `USER_MESSAGE`. |
| **Action** | `domain/Action` | O que sai: `SEND_MESSAGE`, `WAIT_INPUT`, `END` (e `DB_QUERY`/`HTTP_REQUEST`, ainda não emitidas). |
| **Variable** | `Map<String,String>` em `ConversationState` | Valores coletados no fluxo. Sempre texto nesta fase. |

## Por que `config` é `Map<String,String>` e não um tipo por nó

Um `MessageNodeConfig`, `InputNodeConfig` etc. daria segurança de tipo em troca de: uma classe nova
por tipo de nó, um discriminador no Jackson e uma migração do JSON gravado a cada campo novo. Com o
mapa, acrescentar `retry` ao INPUT é uma linha no validador. A segurança que se perde é recuperada no
`WorkflowValidator`, que roda antes de qualquer gravação e cobre exatamente os campos obrigatórios.

## Por que variáveis são sempre texto

O canal entrega texto. Tipar aqui exigiria declarar o tipo esperado no nó, decidir o que fazer quando
a conversão falha e definir coerção nas comparações — três decisões de linguagem que pertencem à fase
de expressões, não ao modelo. `ConditionEvaluator` já converte para número onde faz sentido
(`gt`, `lt`, `gte`, `lte`).

## Relações

```
Workflow 1 ──── * Node
Workflow 1 ──── * Conversation
Conversation 1 ── * Event   (histórico append-only: IN do usuário, OUT do fluxo)
Conversation 1 ── 1 ConversationState ── * Variable
```
