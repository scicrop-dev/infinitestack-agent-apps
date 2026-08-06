Chatbot Workflow Manager — InfiniteStack Agent App
===============================================

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
  SEND_DOCUMENT  anexa um arquivo (PDF, planilha) do diretório de documentos

Instalação:
  plugininstall -path chatbot-workflow-manager-1.1.0.ispz

Banco suportado: postgres

Tabelas criadas automaticamente no primeiro boot (CREATE TABLE IF NOT EXISTS),
no schema default da conexão injetada pelo IS:
  chatbot_workflow      definição JSON dos fluxos
  chatbot_conversation  estado de execução (status, nó atual, variáveis, pilha)
  chatbot_event         histórico de mensagens de entrada e saída

Na primeira execução, se não houver nenhum fluxo cadastrado, um fluxo de
demonstração ("Atendimento — Demo") é inserido para que o painel já tenha o que
executar.

Painel (Agents -> Apps -> Chatbot Workflow Manager):
  Coluna 1: lista de fluxos
  Coluna 2: aba Definição (editor JSON validado) e aba Blocos (documentação de
            cada nó, com exemplo inserível no fluxo aberto)
  Coluna 3: chat de teste, que executa o fluxo selecionado de ponta a ponta

Acesso: ADMIN e SCICROP. Como um fluxo pode consultar o banco do cliente, o
painel não é liberado a USER — o usuário final conversa pelo canal, não pelo
editor.

Configuração das ações de efeito (variáveis de ambiente):
  CHATBOT_DB_ACTIONS_ENABLED     default true  — SELECT somente leitura
  CHATBOT_HTTP_ACTIONS_ENABLED   default false — chamadas HTTP externas
  CHATBOT_HTTP_ALLOWED_HOSTS     lista de hosts liberados, separada por vírgula
                                 (vazia = nada liberado, mesmo com HTTP ligado)
  CHATBOT_DOCUMENTS_DIR          raiz para caminhos relativos do SEND_DOCUMENT
                                 (default: <install>/documents — versionado!)
  CHATBOT_DOCUMENTS_ALLOWED_ROOTS  raízes permitidas, separadas por vírgula
                                 (vazio = sem restrição)

Variáveis de sistema:
  Todo fluxo já começa com is_channel (whatsapp, telegram, teams, insights ou
  ui) e is_user_id (quem está falando, como o canal informou). O prefixo is_ é
  reservado — nenhum nó pode gravar nelas; a validação recusa. Servem para o
  fluxo se adaptar ao canal, por exemplo só anexar arquivo onde ele chega como
  documento nativo.

Arquivos enviáveis:
  O nó SEND_DOCUMENT aceita caminho: relativo resolve contra
  CHATBOT_DOCUMENTS_DIR, absoluto vale como escrito.

  ATENÇÃO ao default: sem CHATBOT_DOCUMENTS_DIR a raiz é <install>/documents, e
  <install> inclui a VERSÃO do plugin — numa atualização os arquivos ficam para
  trás. Em produção aponte para um caminho persistente.

  O que o campo NÃO aceita é um valor interpolado com caminho: o template é do
  autor do fluxo, mas o que vem da conversa não pode conter separador nem "..".
  Para restringir também os caminhos do autor, preencha
  CHATBOT_DOCUMENTS_ALLOWED_ROOTS (vazio = sem restrição).

  No WhatsApp o arquivo chega como documento nativo (requer o /send-document no
  sidecar Baileys).

API (prefixada por /api/plugins/chatbot-workflow-manager):
  GET    /api/runtime-health          "ok" — usado pelo IS no boot
  GET    /api/status                  estado do schema e contadores
  GET    /api/workflows               lista de fluxos
  GET    /api/workflows/{id}          um fluxo
  PUT    /api/workflows/{id}          cria/atualiza (valida antes de gravar)
  DELETE /api/workflows/{id}          remove
  POST   /api/workflows/validate      valida sem gravar
  POST   /api/chat/start              inicia conversa {workflowId}       (painel)
  GET    /api/chat/{conversationId}   estado + histórico                  (painel)
  POST   /api/chat/{conversationId}/message   envia texto do usuário      (painel)
  POST   /api/chat/resume             porta dos canais externos           (adapters)

/api/chat/resume é o que liga o app aos canais do IS. O chamador informa
{workflowId, channel, channelUserId, text} — sem guardar conversationId — e o
app decide entre retomar a conversa em andamento e começar uma nova. Devolve só
as mensagens daquele turno.

Ligação com WhatsApp/Telegram/Teams:
  Em ChatBotSettings, escolha o workflow no seletor de agente do canal. O
  backend grava agent_id = "__workflow__:<workflowId>" e o ChatService roteia
  para cá via PluginWorkflowAskService, sem código por canal.
