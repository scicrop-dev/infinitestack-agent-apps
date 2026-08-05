Chat App — InfiniteStack Agent App
==================================

Motor de workflow conversacional stateful e agnóstico de canal. Um workflow é
um grafo de nós percorrido por uma conversa que guarda variáveis e o nó atual
entre uma mensagem e outra.

Nós disponíveis:
  MESSAGE        envia texto e segue
  INPUT          pergunta, pausa e grava a resposta numa variável
  IF             ramifica (operador simples ou expressão)
  SET_VARIABLE   atribui (valor com {{var}} ou expressão)
  END            encerra — ou retorna, se estiver dentro de um subfluxo
  CALL_WORKFLOW  chama outro fluxo, com escopo isolado (input/output)
  DB_QUERY       SELECT no banco de destino, somente leitura e parametrizado
  HTTP_REQUEST   GET/POST em serviço externo (desligado por padrão)

Instalação:
  plugininstall -path chat-app-1.1.0.ispz

Banco suportado: postgres

Tabelas criadas automaticamente no primeiro boot (CREATE TABLE IF NOT EXISTS),
no schema default da conexão injetada pelo IS:
  chatapp_workflow      definição JSON dos fluxos
  chatapp_conversation  estado de execução (status, nó atual, variáveis, pilha)
  chatapp_event         histórico de mensagens de entrada e saída

Na primeira execução, se não houver nenhum fluxo cadastrado, um fluxo de
demonstração ("Atendimento — Demo") é inserido para que o painel já tenha o que
executar.

Painel (Agents -> Apps -> Chat App):
  Coluna 1: lista de fluxos
  Coluna 2: aba Definição (editor JSON validado) e aba Blocos (documentação de
            cada nó, com exemplo inserível no fluxo aberto)
  Coluna 3: chat de teste, que executa o fluxo selecionado de ponta a ponta

Acesso: ADMIN e SCICROP. Como um fluxo pode consultar o banco do cliente, o
painel não é liberado a USER — o usuário final conversa pelo canal, não pelo
editor.

Configuração das ações de efeito (variáveis de ambiente):
  CHATAPP_DB_ACTIONS_ENABLED     default true  — SELECT somente leitura
  CHATAPP_HTTP_ACTIONS_ENABLED   default false — chamadas HTTP externas
  CHATAPP_HTTP_ALLOWED_HOSTS     lista de hosts liberados, separada por vírgula
                                 (vazia = nada liberado, mesmo com HTTP ligado)

API (prefixada por /api/plugins/chat-app):
  GET    /api/runtime-health          "ok" — usado pelo IS no boot
  GET    /api/status                  estado do schema e contadores
  GET    /api/workflows               lista de fluxos
  GET    /api/workflows/{id}          um fluxo
  PUT    /api/workflows/{id}          cria/atualiza (valida antes de gravar)
  DELETE /api/workflows/{id}          remove
  POST   /api/workflows/validate      valida sem gravar
  POST   /api/chat/start              inicia conversa {workflowId}
  GET    /api/chat/{conversationId}   estado + histórico
  POST   /api/chat/{conversationId}/message   envia texto do usuário
