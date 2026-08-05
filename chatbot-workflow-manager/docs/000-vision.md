# Visão

**Conversational Workflow Engine** — motor de fluxos conversacionais *stateful* e agnóstico de canal,
empacotado como Agent App do InfiniteStack.

## O problema

Um atendimento automatizado não é uma sequência de perguntas e respostas independentes: ele precisa
lembrar o que já foi dito, ramificar conforme a resposta e retomar exatamente de onde parou quando o
usuário responde meia hora depois. Escrever isso em código, canal por canal, produz um emaranhado de
`if` dentro do adapter de cada canal — e o mesmo fluxo tem que ser reimplementado no WhatsApp, no
Telegram e na web.

## A proposta

Separar **o que o fluxo faz** (uma definição declarativa em JSON) de **quem entrega a mensagem**
(o adapter do canal). O motor recebe um evento, percorre o grafo e devolve ações; quem executa as
ações é o canal.

```
definição do fluxo (JSON)  ──┐
                             ├──►  motor  ──►  ações  ──►  adapter  ──►  usuário
estado da conversa (banco) ──┘
```

Consequências dessa escolha:

- **Um fluxo, todos os canais.** O motor não sabe se está falando com WhatsApp ou com a UI de teste.
- **Estado explícito e persistido.** A conversa vive no banco, não na memória do processo — reinício
  do plugin não perde atendimento em andamento.
- **Fluxo é dado, não código.** Alterar o atendimento não exige build, deploy nem programador.
- **Motor testável isoladamente.** Sendo função pura de (fluxo, estado, evento), roda em teste
  unitário sem banco, sem HTTP e sem mocks.

## Fora de escopo nesta fase

Editor visual de grafo, subfluxos, linguagem de expressões e ações de efeito colateral
(consulta a banco, chamada HTTP) — ver [008-roadmap.md](008-roadmap.md).
