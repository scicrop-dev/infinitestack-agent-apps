# Ações

O motor devolve **intenções**, nunca efeitos. Quem executa é o adapter do canal — é o que permite o
mesmo fluxo rodar no chat de teste do painel hoje e em WhatsApp ou Telegram depois, sem tocar no
motor.

## Implementadas

| Ação | Emitida por | Efeito no adapter |
|---|---|---|
| `SEND_MESSAGE` | `MESSAGE`, `INPUT` (prompt), `END` (texto), e o próprio motor em caso de erro | Entrega o texto ao usuário |
| `WAIT_INPUT` | `INPUT` | Sinaliza que o turno acabou e a próxima mensagem do usuário é a resposta |
| `END` | `END` | Sinaliza encerramento da conversa |

Só `SEND_MESSAGE` vira linha de diálogo em `chatbot_event`. `WAIT_INPUT` e `END` são transições de
estado, já refletidas no status da conversa — gravá-las produziria mensagens vazias no chat.

| `DB_QUERY` | nó `DB_QUERY` | Consulta já executada pelo Action Executor — a ação registra o rastro (linhas, parâmetros) |
| `HTTP_REQUEST` | nó `HTTP_REQUEST` | Idem (status, método, host) |

## O Action Executor

`DB_QUERY` e `HTTP_REQUEST` são as únicas ações com efeito fora da conversa, e nenhuma delas é
executada pelo motor: quem executa é a interface `ActionExecutor`, injetada por chamada. O motor
decide *que* o efeito deve acontecer e com quais parâmetros; abrir conexão é responsabilidade de
outra camada.

**Efeitos são síncronos dentro do turno** — um `DB_QUERY` precisa devolver valor antes de o `IF`
seguinte decidir o ramo, e adiar exigiria partir o turno em dois e reintroduzir no motor o estado
que ele não tem. O custo é que consulta lenta segura a thread da mensagem; por isso todo efeito tem
timeout obrigatório.

### Postura de segurança

| | Padrão | Por quê |
|---|---|---|
| **Banco** | ligado, somente leitura, parametrizado | É o caso de uso central, e o datasource já é o do host. O que protege não é o interruptor: é `SqlGuard` — só SELECT, comando único, e variável **exclusivamente** como parâmetro JDBC. SQL com `{{variavel}}` é recusado na gravação, porque interpolar o que o usuário digitou no chat é injeção. |
| **HTTP** | desligado, allowlist obrigatória | Instalação air-gapped é cenário real no IS, e chamar URL arbitrária é saída de dados do perímetro. Ligar exige duas decisões: habilitar **e** listar hosts. Habilitar sem listar não libera nada. Redirects não são seguidos — escapariam da allowlist. |

Propriedades: `chatbot.actions.db.{enabled,max-rows,timeout-seconds}` e
`chatbot.actions.http.{enabled,allowed-hosts,timeout-seconds,max-response-bytes}`.

> Como um fluxo passa a poder consultar o banco do cliente, o painel foi restringido a
> `ADMIN` e `SCICROP` em `plugin-permissions.json`. Usuário final não precisa dele: ele conversa
> pelo canal, não pelo editor.

## Contrato de execução

As ações vêm **ordenadas** e devem ser executadas nessa ordem: um fluxo que envia duas mensagens
antes de pausar depende disso para não inverter a pergunta e o contexto.

Ação que falha ao ser entregue é problema do adapter, não do motor — o estado da conversa já foi
calculado e gravado. Um adapter que precise de garantia de entrega (retry, confirmação) implementa
isso na sua camada, como o `BaileysClient` faz no `infinitestack-back`.
