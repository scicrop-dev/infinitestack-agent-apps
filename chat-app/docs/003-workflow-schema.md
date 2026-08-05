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
| `id` | sim | Identificador único. Vira a chave primária em `chatapp_workflow` e precisa bater com o id da URL no `PUT`. |
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
