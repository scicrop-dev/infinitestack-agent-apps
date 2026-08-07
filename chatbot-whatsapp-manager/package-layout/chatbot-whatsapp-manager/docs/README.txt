Chatbot WhatsApp Manager — InfiniteStack Agent App
==================================================

Painel de conversas do WhatsApp conectado via Baileys: guarda todo o tráfego,
mostra cada conversa e permite responder com texto, imagem ou documento — além
de assumir o atendimento, calando o bot enquanto um humano conduz.

Instalação:
  plugininstall -path chatbot-whatsapp-manager-1.0.0.ispz

Acesso: ADMIN e SCICROP.
Banco suportado: postgres

Tabela criada automaticamente no primeiro boot, no schema "apps" (criado pelo
próprio app com CREATE SCHEMA IF NOT EXISTS). Nada é escrito em public:
  apps.chatbot_whatsapp_manager_message   o transcript das conversas

COMO O TRANSCRIPT É ALIMENTADO
------------------------------
O backend captura nas duas únicas portas por onde uma mensagem do WhatsApp passa
— BaileysService (entrada) e BaileysClient (saída) — e empurra cada uma para
POST /api/transcript deste app, que a grava em chatbot_whatsapp_manager_message.

Por isso o registro inclui o que o histórico de chat do IS nunca viu: o fluxo
/entrar (OTP), as recusas de não autenticado, as mensagens de progresso, as
imagens, os PDFs e o que um operador digitou neste painel.

Mídia é registrada por REFERÊNCIA (tipo e nome do arquivo), nunca por conteúdo:
o transcript existe para ser lido por uma pessoa, e o base64 de um PDF de 10 MB
não serve a isso.

Cada mensagem guarda quem a produziu — contact, bot ou operator — que é o que
permite abrir uma conversa antiga e saber se aquela resposta veio da automação
ou de um colega.

LIMITE CONHECIDO: a entrega do backend para cá é best-effort com uma retentativa
e fila limitada. Uma reinicialização deste app é coberta; uma indisponibilidade
longa perde as mensagens ocorridas durante ela. Garantir entrega exigiria um
broker ou spool em disco — deliberadamente fora de escopo.

Conversas anteriores à captura só existem no histórico do IS. O painel cai nele
automaticamente quando não há transcript para o contato, marcando a conversa
como "histórico anterior à captura — parcial".

ATENDIMENTO HUMANO
------------------
O botão "Assumir atendimento" chama POST /api/admin/baileys/attendance no back.
Enquanto ativo, NADA automático sai para aquele número — nem resposta de agente,
nem o pedido de autenticação. Duas vozes na mesma conversa, possivelmente se
contradizendo, é pior que uma só.

O atendimento expira por inatividade (WHATSAPP_ATTENDANCE_TTL_MINUTES, padrão
60; 0 desliga). O motivo é o modo de falha real: alguém esquece de liberar e o
contato nunca mais é atendido pelo bot, sem ninguém perceber, porque silêncio
parece cliente quieto. Cada mensagem manual renova o prazo.

ROTAS
-----
Deste app:
  POST /api/transcript          recebe do backend (127.0.0.1, não do navegador)
  GET  /api/messages?jid=       conversa de um contato
  GET  /api/conversations       contatos com mensagem, mais recentes primeiro
  GET  /api/status              schema e contagem
  GET  /api/runtime-health      "ok" — não toca o banco, de propósito

Do host, chamadas pelo navegador do operador (mesma origem, sessão dele):
  GET  /api/admin/channel-users            contatos
  GET  /api/admin/users                    nome e email
  GET  /api/admin/chathistory              histórico antigo (reserva)
  GET  /api/admin/baileys/status           estado da conexão
  GET  /api/admin/baileys/attendance       quem está sendo atendido
  POST /api/admin/baileys/attendance       assumir / liberar
  POST /api/admin/baileys/send             enviar texto
  POST /api/admin/baileys/send-image       enviar imagem
  POST /api/admin/baileys/send-document    enviar documento
  POST /api/admin/baileys/send-video       vídeo, ou GIF com gifPlayback=true
  POST /api/admin/baileys/send-audio       áudio, ou nota de voz com ptt=true
  POST /api/admin/baileys/send-location    latitude/longitude, com nome opcional
  POST /api/admin/baileys/send-contact     um ou mais contatos, como vCard
  POST /api/admin/baileys/send-reaction    emoji sobre uma mensagem existente

RESPOSTA CITANDO (reply/quote)
------------------------------
Qualquer envio de texto ou imagem aceita quotedMessageId, quotedText e
quotedFromMe — os três transformam o envio numa resposta citando uma mensagem
anterior. Todo envio devolve { ok, messageId }, e é esse messageId que permite
citar ou reagir àquela mensagem depois: a chave de uma mensagem no WhatsApp não
é reconstruível a partir do conteúdo, por isso ela é guardada em
wa_message_id no transcript.

GIF é vídeo, não o formato GIF: o WhatsApp o modela como vídeo curto marcado
para tocar em laço e sem som. E ptt (push-to-talk) muda a aparência do áudio —
com ela chega a bolha de voz gravada, sem ela chega como arquivo de música.

Reação nunca vira bolha nova na conversa: ela referencia a mensagem alvo, e
emoji vazio REMOVE a reação, que é como o próprio WhatsApp modela "desreagir".

ENVIO RESTRITO A CONTATOS CONHECIDOS
------------------------------------
O backend recusa enviar para JID que não esteja em channel_users. Além de ser o
propósito do painel (responder a quem já falou conosco), Baileys é um cliente
não oficial rodando numa conta real: permitir mensagem para número arbitrário
transformaria a ferramenta de suporte em algo capaz de fazer a conta ser banida.
