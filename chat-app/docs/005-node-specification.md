# Especificação dos nós

## MESSAGE — envia texto e segue

| Config | Obrigatório | Descrição |
|---|---|---|
| `text` | sim | Texto a enviar. Aceita `{{variavel}}`. |

```json
{ "id": "saudacao", "type": "MESSAGE", "next": "menu", "config": { "text": "Prazer, {{nome}}!" } }
```

## INPUT — pergunta e pausa

O único nó que interrompe o turno. A próxima mensagem do usuário é gravada na variável e o fluxo
retoma do `next`.

| Config | Obrigatório | Descrição |
|---|---|---|
| `variable` | sim | Nome da variável que recebe a resposta. |
| `prompt` | não | Pergunta a enviar antes de pausar. Omitido quando um `MESSAGE` anterior já perguntou — emitir texto vazio geraria uma mensagem em branco no canal. |

```json
{ "id": "pergunta-nome", "type": "INPUT", "next": "saudacao",
  "config": { "prompt": "Qual é o seu nome?", "variable": "nome" } }
```

## IF — ramifica

Não usa `next`: os destinos são `then` e `else`, ambos obrigatórios.

| Config | Obrigatório | Descrição |
|---|---|---|
| `variable` | sim | Variável do lado esquerdo. |
| `operator` | não (padrão `eq`) | Ver tabela abaixo. |
| `value` | não | Lado direito. Aceita `{{variavel}}`. |
| `then` / `else` | sim | Nós de destino. |

| Operador | Semântica |
|---|---|
| `eq`, `neq` | Igualdade de texto, com `trim` e **sem** diferenciar maiúsculas |
| `contains`, `not_contains` | Substring, mesma tolerância |
| `empty`, `not_empty` | Variável vazia após `trim` |
| `gt`, `gte`, `lt`, `lte` | Comparação **numérica**. Se algum lado não for número, a condição é falsa |

> Comparação frouxa em `eq`/`neq`/`contains` é deliberada: o lado esquerdo é texto digitado por um
> humano num chat, e `"sim "` não pode falhar contra `"sim"`. Já `gt`/`lt` são estritamente
> numéricos — `"10" < "9"` por ordem alfabética é o tipo de bug que só aparece com o dado certo.

```json
{ "id": "roteia", "type": "IF",
  "config": { "variable": "opcao", "operator": "eq", "value": "1",
              "then": "consulta", "else": "invalida" } }
```

## SET_VARIABLE — atribui

| Config | Obrigatório | Descrição |
|---|---|---|
| `variable` | sim | Nome da variável. |
| `value` | não (padrão vazio) | Valor. Aceita `{{variavel}}`, o que permite compor a partir de outras. |

## END — encerra

| Config | Obrigatório | Descrição |
|---|---|---|
| `text` | não | Mensagem de despedida. |

Sem `next`. Leva a conversa a `FINISHED`.

## CALL_WORKFLOW — chama outro fluxo

Executa outro fluxo e retorna para o `next` quando ele terminar.

| Config | Obrigatório | Descrição |
|---|---|---|
| `workflow` | sim | Id do fluxo a executar. |
| `input` | não | Variáveis passadas ao filho, separadas por vírgula. |
| `output` | não | Variáveis do filho trazidas de volta, separadas por vírgula. |

> **Escopo isolado.** O filho só enxerga o que está em `input` e só devolve o que está em `output`.
> Compartilhar todas as variáveis tornaria os dois campos decorativos e faria um subfluxo
> reutilizável sobrescrever, sem aviso, uma variável homônima do chamador — o bug clássico de fluxo
> grande.

Profundidade máxima: `chatapp.engine.max-call-depth` (10). Um `INPUT` dentro do subfluxo pausa
normalmente e a conversa retoma lá dentro — a pilha é persistida.

```json
{ "id": "coleta-endereco", "type": "CALL_WORKFLOW", "next": "confirma",
  "config": { "workflow": "endereco", "input": "nome", "output": "cep,cidade" } }
```

## DB_QUERY — consulta o banco

| Config | Obrigatório | Descrição |
|---|---|---|
| `sql` | sim | `SELECT` (ou `WITH…SELECT`) com parâmetros nomeados `:assim`. |
| `params` | não | Variáveis a ligar aos parâmetros, separadas por vírgula. |
| `output` | não | `coluna:variavel, outra:variavel2`, da primeira linha. Omitido, todas as colunas viram variáveis com o nome da coluna. |
| `countInto` | não | Variável que recebe a quantidade de linhas. |

> **Variável nunca entra como texto no SQL.** Use `:nome` e declare em `params`; o valor é ligado
> como parâmetro JDBC. SQL contendo `{{variavel}}` é **recusado na gravação** — interpolar o que o
> usuário digitou no chat dentro de uma consulta é injeção de SQL, e recusar em silêncio faria o
> autor achar que funcionou.

Somente leitura, comando único, no máximo 100 linhas e 5s. Falha interrompe a conversa.

```json
{ "id": "busca-pedido", "type": "DB_QUERY", "next": "achou",
  "config": { "sql": "SELECT status, valor FROM pedidos WHERE numero = :protocolo",
              "params": "protocolo",
              "output": "status:situacao, valor:valor_pedido",
              "countInto": "encontrados" } }
```

## HTTP_REQUEST — chama um serviço

| Config | Obrigatório | Descrição |
|---|---|---|
| `url` | sim | `http://` ou `https://`. Aceita `{{variavel}}` (valores são URL-encoded). |
| `method` | não | `GET` (padrão) ou `POST`. |
| `body` | não | Corpo do POST. Aceita `{{variavel}}`. |
| `headers` | não | `Nome=valor, Nome2=valor2`. |
| `output` | não | `caminho.no.json:variavel` — o caminho desce por objeto e por índice de array. |
| `statusInto` | não | Variável que recebe o código HTTP. |

> **Vem desligado.** Só funciona com `chatapp.actions.http.enabled=true` **e**
> `chatapp.actions.http.allowed-hosts` preenchida. Instalação air-gapped é cenário real no IS, e
> chamar URL arbitrária é saída de dados do perímetro. Redirects não são seguidos — escapariam da
> allowlist.

```json
{ "id": "consulta-cep", "type": "HTTP_REQUEST", "next": "mostra",
  "config": { "url": "https://viacep.com.br/ws/{{cep}}/json/",
              "output": "logradouro:rua, localidade:cidade",
              "statusInto": "http_status" } }
```

## Expressões (`expression`)

Disponível em `IF` e `SET_VARIABLE` como alternativa à forma simples.

```
Operadores   ==  !=  <  <=  >  >=   +  -  *  /  %   &&/and  ||/or  !/not  ( )
Funções      len  lower  upper  trim  isEmpty  isNumber  number
             contains(a,b)  startsWith(a,b)  endsWith(a,b)  default(a,b)
Literais     'texto'   42   3.14   true   false
```

Nome sem aspas é variável; com aspas é texto. `+` soma se os dois lados forem números e concatena
caso contrário.

> **Uma divergência intencional da forma simples.** `gt`/`lt` com valor não numérico devolvem
> **falso**; em expressão, `<` e `>` **falham** e a conversa vai para `ERROR`. Quem escreve
> expressão está escrevendo código, e um `<` que vira falso em silêncio esconde justamente o dado
> malformado que se queria detectar. Já `==` compara igual a `eq` (sem caixa, sem espaços das
> pontas) para as duas formas não discordarem.

```json
{ "id": "avalia", "type": "IF",
  "config": { "expression": "opcao == '1' && len(nome) > 2", "then": "ok", "else": "erro" } }
```
