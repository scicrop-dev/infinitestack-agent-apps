# Motor de execução

```java
EngineResult advance(Workflow root, ConversationState state, Event event, EngineContext ctx)
```

Sem banco, sem HTTP, sem canal — as duas portas para o mundo externo entram pelo `EngineContext`,
como parâmetros da chamada:

| Porta | Interface | Para quê |
|---|---|---|
| Subfluxos | `WorkflowResolver` | resolver um fluxo por id |
| Efeitos | `ActionExecutor` | executar `DB_QUERY` e `HTTP_REQUEST` |

É por isso que um fluxo com consulta a banco e subfluxo aninhado roda em teste unitário com um mapa
e um lambda, sem subir Spring.

## Um turno

1. **Aplica o evento.**
   - `START`: posiciona no `start` do fluxo, status `RUNNING`.
   - `USER_MESSAGE`: o nó atual tem que ser um `INPUT` — grava o texto (com `trim`) na variável
     declarada e avança para o `next`.
   - `USER_MESSAGE` em conversa que não aceita entrada: responde avisando e **não altera o estado**.
2. **Percorre nós** em sequência, acumulando ações, até um destes desfechos:

| Desfecho | Status final |
|---|---|
| Chegou a um `INPUT` | `WAITING_INPUT` — pausa esperando o usuário |
| Chegou a um `END` na raiz | `FINISHED` |
| Chegou a um `END` dentro de subfluxo | volta ao chamador e continua |
| Erro de fluxo | `ERROR` |
| Estourou `max-steps-per-turn` | `ERROR` |
| Estourou `max-call-depth` | `ERROR` |

## Os dois tetos

`chatapp.engine.max-steps-per-turn` (50) e `chatapp.engine.max-call-depth` (10) protegem contra
riscos diferentes: o primeiro contra ciclo entre nós (`a → b → a`), o segundo contra recursão entre
fluxos (`A chama B que chama A`). O de passos sozinho até barraria a recursão, mas só depois de
empilhar dezenas de frames com uma cópia do escopo em cada — e o erro apontaria "ciclo" quando o
problema é profundidade.

## O teto de passos por turno

`chatapp.engine.max-steps-per-turn` (padrão 50). Nada no schema impede `a → b → a`: sem o teto, esse
fluxo prenderia a thread da requisição para sempre e derrubaria o app por causa de um JSON mal
escrito. Com ele, a conversa vai para `ERROR` com a mensagem apontando o ciclo.

O teto é **por turno**, não por conversa: um fluxo que volta ao menu vinte vezes é legítimo, porque
cada volta consome um turno diferente — o `INPUT` zera a contagem.

## Erro de fluxo não é exceção

Referência quebrada, `config` faltando, ramo ausente: tudo vira `EngineResult` com status `ERROR` e
mensagem em `error()`. É dado a ser mostrado ao autor do fluxo, não falha do processo. O usuário
final recebe uma mensagem neutra; a causa técnica vai para o histórico e para o painel.

## Subfluxos e escopo

`CALL_WORKFLOW` empilha um frame com (fluxo de retorno, nó de retorno, **escopo do chamador**,
lista de saída) e troca o escopo pelo que foi declarado em `input`. No `END` do filho, o escopo do
pai é restaurado e só as variáveis de `output` são copiadas de volta.

Guardar o escopo inteiro no frame — em vez de só as diferenças — é o que garante que o filho não
consiga alterar variável do pai que não esteja em `output`.

## Ações

O motor devolve **intenções**, não efeitos. Quem envia é o adapter. `SEND_MESSAGE` é a única que
vira linha de diálogo — `WAIT_INPUT` e `END` são transições de estado, já refletidas no status, e
`DB_QUERY`/`HTTP_REQUEST` são registros de rastro do efeito que já aconteceu.

Efeito que falha **interrompe a conversa**. Seguir com variáveis vazias levaria o `IF` seguinte a
tomar o ramo do "não encontrado" quando o caso real é "o banco caiu", e ninguém descobriria a
diferença lendo o histórico.
