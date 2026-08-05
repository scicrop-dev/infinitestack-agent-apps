# Roadmap

| Fase | Escopo | Situação |
|---|---|---|
| **1 — Core** | Domínio (Workflow, Node, Conversation, Event, Action), parser e validador | ✅ entregue |
| **2 — Nós** | `MESSAGE`, `INPUT`, `IF`, `SET_VARIABLE`, `END` | ✅ entregue |
| **3 — Ações** | `DB_QUERY` e `HTTP_REQUEST` dentro do fluxo | ✅ entregue |
| **4 — Runtime** | Persistência Postgres, painel com editor validado e chat de teste | ✅ entregue |
| **5 — Subfluxos** | `CALL_WORKFLOW`: pilha de execução, passagem e retorno de variáveis | ✅ entregue |
| **6 — Expressões** | Linguagem de expressões e condições compostas | ✅ entregue |

## Entregue na 1.1.0

Todas as seis fases. Além do que a 1.0.0 já trazia (motor puro, cinco tipos de nó conversacionais,
interpolação, nove operadores, validação com erros e avisos, persistência em três tabelas, painel de
uma tela e fluxo de demonstração):

- **Fase 3** — nós `DB_QUERY` e `HTTP_REQUEST` pela interface `ActionExecutor`, com SQL somente
  leitura e parametrizado, e HTTP desligado por padrão com allowlist de hosts.
- **Fase 5** — `CALL_WORKFLOW` com pilha de chamada persistida, escopo isolado por `input`/`output`
  e teto de profundidade.
- **Fase 6** — linguagem de expressões (lexer, parser recursivo-descendente, avaliador com cache de
  árvores) disponível em `IF` e `SET_VARIABLE`.
- **Documentação na tela** — aba *Blocos* no painel, com referência de cada nó, campos, exemplo e
  inserção no fluxo aberto.

62 testes unitários entre motor, validador, expressões, subfluxos, efeitos e exemplos das docs.

## O que a 1.0.0 entregou

Fases 1, 2 e 4: motor com os cinco nós conversacionais, validação, persistência e painel.

## Fora do roadmap

**Editor visual de grafo** — exige vendorar uma biblioteca de canvas em `static/` e é praticamente
um projeto à parte. A aba *Blocos* cobre a lacuna por outro caminho: documenta cada nó na tela e
insere o exemplo pronto no fluxo aberto.

**Adapters de canal externos** (WhatsApp/Telegram) — a interface do `ChatController` já é a que um
adapter usaria (`channel` e `channelUserId` existem desde o `start`), mas rotear um canal do host
para o plugin exige mudança no `infinitestack-back`.

**Pausa administrativa** — `ConversationStatus.PAUSED` existe e já é tratado pelo motor (mensagem
recebida em conversa pausada não altera o estado), mas nada o atribui: falta o endpoint.
