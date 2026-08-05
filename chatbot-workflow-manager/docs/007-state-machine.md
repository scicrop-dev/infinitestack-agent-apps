# Máquina de estados

```
                    ┌──────────────┐
      START ───────►│   RUNNING    │  (transitório — só dentro de um turno)
                    └──────┬───────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
   ┌─────────────┐  ┌────────────┐   ┌───────────┐
   │WAITING_INPUT│  │  FINISHED  │   │   ERROR   │
   └──────┬──────┘  └────────────┘   └───────────┘
          │              (terminal)      (terminal)
          │ USER_MESSAGE
          └──────► RUNNING
```

| Estado | Significado | Aceita mensagem? |
|---|---|---|
| `RUNNING` | O motor está percorrendo nós **agora** | — |
| `WAITING_INPUT` | Parou num `INPUT`, esperando o usuário | **sim** |
| `PAUSED` | Pausa administrativa | não |
| `ERROR` | Erro de fluxo — interrompida | não |
| `FINISHED` | Chegou a um `END` | não |

## `RUNNING` nunca aparece gravado

É transitório por construção: todo turno termina em `WAITING_INPUT`, `FINISHED` ou `ERROR` antes de
`ConversationService` gravar. Uma linha em `RUNNING` no banco significa que o processo morreu no meio
de um turno — é um sinal de diagnóstico, não um estado de repouso.

## `PAUSED` é reservado

O motor nunca o atribui sozinho. Existe para a pausa administrativa (suspender um atendimento sem
encerrá-lo) prevista no roadmap. Já é tratado hoje: mensagem recebida em conversa pausada recebe
resposta e **não** altera o estado.

## Mensagem fora de hora não quebra nada

Em estado terminal, o usuário recebe *"Esta conversa já foi encerrada"* e o estado permanece
idêntico. Ignorar em silêncio seria pior: no canal real o usuário não tem como saber que acabou.

## Erro é estado, não exceção

Referência quebrada, `config` faltando, ciclo: tudo leva a `ERROR` com a causa em `EngineResult.error()`.
O usuário final vê uma mensagem neutra; a causa técnica vai para `chatbot_event` e para o painel.
