# Schema do workflow

```json
{
  "id": "atendimento-demo",
  "name": "Atendimento — Demo",
  "start": "boas-vindas",
  "nodes": [
    { "id": "boas-vindas", "type": "MESSAGE", "next": "pergunta",
      "config": { "text": "Olá!" } }
  ]
}
```

## Campos do workflow

| Campo | Obrigatório | Descrição |
|---|---|---|
| `id` | sim | Identificador único. Vira a chave primária em `chatbot_workflow` e precisa bater com o id da URL no `PUT`. |
| `name` | não (aviso) | Título exibido na lista do painel. |
| `start` | sim | Id do primeiro nó. Precisa existir em `nodes`. |
| `nodes` | sim | Array de nós — ou, alternativamente, um objeto `{ "id-do-no": { ... } }`. |

## Campos do nó

| Campo | Obrigatório | Descrição |
|---|---|---|
| `id` | sim | Único dentro do fluxo. |
| `type` | sim | `MESSAGE`, `INPUT`, `IF`, `SET_VARIABLE`, `END`. |
| `next` | depende do tipo | Próximo nó. Não se aplica a `IF` (ramifica por `then`/`else`) nem a `END`. |
| `config` | depende do tipo | Parâmetros do tipo — ver [005-node-specification.md](005-node-specification.md). |

## Interpolação

Qualquer campo textual de `config` aceita `{{variavel}}`, substituído pelo valor coletado na
conversa. Variável não definida vira string vazia — nunca a chave crua no texto entregue ao usuário.

```json
{ "id": "saudacao", "type": "MESSAGE", "next": "menu",
  "config": { "text": "Prazer, {{nome}}!" } }
```

## Variáveis de sistema (`is_*`)

Toda conversa já nasce com estas preenchidas — não é preciso perguntar nem calcular:

| Variável | Contém |
|---|---|
| `is_channel` | `whatsapp`, `telegram`, `teams`, `insights` ou `ui` (chat de teste do painel) |
| `is_user_id` | identidade de quem está falando, como o canal a informou |

O prefixo `is_` é **reservado**: nenhum nó pode gravar numa variável que comece com ele, e tentar
isso é erro na validação. Sobrescrever `is_channel` não falharia em lugar nenhum — só faria cada
ramo que depende dele tomar o caminho errado, sem rastro na conversa. Ler é sempre permitido.

Duas propriedades que valem saber:

- **Sobrevivem ao reinício da conversa.** O `START` zera os dados coletados, mas não o contexto:
  a segunda conversa da mesma pessoa não começa cega.
- **Atravessam `CALL_WORKFLOW` sem estar em `input`.** São contexto da conversa, não dado do
  chamador — exigir que cada chamada as declarasse seria ruído, e esquecer faria um subfluxo que
  ramifica por canal tomar o caminho errado em silêncio.

```json
{ "id": "pode-anexar", "type": "IF",
  "config": { "expression": "is_channel == 'whatsapp' || is_channel == 'insights'",
              "then": "envia-pdf", "else": "manda-link" } }
```

## As duas formas de declarar `nodes`

Array (canônica) e mapa `id→nó` são equivalentes; no mapa, a chave vira o `id` do nó. As duas existem
porque a diferença é puramente sintática e recusar uma delas custaria ao autor reescrever o fluxo
inteiro.

## Regras verificadas na gravação

**Erros** (impedem gravar): `id`/`start` ausentes, `start` inexistente, id de nó duplicado ou
ausente, `type` inválido, `config` obrigatória faltando, `next` ausente ou apontando para nó que não
existe, ramo `then`/`else` ausente ou quebrado, operador de `IF` desconhecido.

**Avisos** (gravam mesmo assim): `name` ausente, nó inalcançável a partir do `start`, nenhum `END`
alcançável.
