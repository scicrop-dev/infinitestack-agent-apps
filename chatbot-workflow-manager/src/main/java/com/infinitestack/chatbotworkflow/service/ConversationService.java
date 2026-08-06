package com.infinitestack.chatbotworkflow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.infinitestack.chatbotworkflow.domain.Action;
import com.infinitestack.chatbotworkflow.domain.Conversation;
import com.infinitestack.chatbotworkflow.domain.ConversationState;
import com.infinitestack.chatbotworkflow.domain.EngineResult;
import com.infinitestack.chatbotworkflow.domain.Event;
import com.infinitestack.chatbotworkflow.domain.Workflow;
import com.infinitestack.chatbotworkflow.engine.ActionExecutor;
import com.infinitestack.chatbotworkflow.engine.EngineContext;
import com.infinitestack.chatbotworkflow.engine.WorkflowEngine;
import com.infinitestack.chatbotworkflow.engine.WorkflowResolver;
import com.infinitestack.chatbotworkflow.repository.ConversationRepository;
import com.infinitestack.chatbotworkflow.repository.SchemaInitializer;

/**
 * Liga o motor (puro) à persistência e ao canal.
 *
 * Toda a orquestração de um turno vive aqui: carregar o fluxo, chamar o motor, gravar o novo
 * estado e registrar no histórico o que entrou e o que saiu. O motor não sabe que existe banco;
 * o adapter de canal não sabe que existe motor. Esta classe é a única que sabe das duas coisas —
 * é o que permite plugar WhatsApp/Telegram depois sem tocar em nenhuma das duas pontas.
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** Uma linha do diálogo, já pronta para a UI. {@code from} é "bot" ou "user". */
    public record Message(String from, String text, Instant at) {}

    /** Retrato completo de uma conversa: estado + histórico. É o que o painel consome. */
    public record View(String conversationId, String workflowId, String status, String currentNodeId,
                       Map<String, String> variables, List<Message> messages, String error) {}

    /**
     * O resultado de um turno para um adapter de canal: só o que o fluxo falou <b>agora</b>.
     *
     * @param messages   textos a entregar, na ordem em que o fluxo os produziu
     * @param startedNew true quando este turno começou uma conversa nova
     */
    public record Turn(String conversationId, String status, List<String> messages,
                       Map<String, String> variables, boolean startedNew, String error) {}

    /** Par (conversa gravada, resultado do motor) — evita reler o banco para montar a resposta. */
    private record Outcome(Conversation conversation, EngineResult result) {}

    private final ConversationRepository repository;
    private final WorkflowService workflowService;
    private final WorkflowEngine engine;
    private final SchemaInitializer schema;
    private final ActionExecutor actionExecutor;

    public ConversationService(ConversationRepository repository,
                               WorkflowService workflowService,
                               WorkflowEngine engine,
                               SchemaInitializer schema,
                               ActionExecutor actionExecutor) {
        this.repository = repository;
        this.workflowService = workflowService;
        this.engine = engine;
        this.schema = schema;
        this.actionExecutor = actionExecutor;
    }

    /**
     * Monta o contexto de um turno.
     *
     * O resolvedor de subfluxos guarda o que já carregou <b>dentro do turno</b>: um fluxo que chama
     * o mesmo subfluxo em ramos diferentes, ou que volta a ele num laço, releria a mesma linha do
     * banco a cada passo. O cache morre com o turno de propósito — assim editar um fluxo pelo painel
     * vale já na próxima mensagem, sem invalidação nenhuma para manter.
     */
    private EngineContext contextFor(Workflow rootWorkflow) {
        Map<String, Workflow> loaded = new HashMap<>();
        loaded.put(rootWorkflow.id(), rootWorkflow);

        WorkflowResolver resolver = workflowId -> loaded.computeIfAbsent(workflowId, id -> {
            try {
                return workflowService.load(id);
            } catch (Exception e) {
                // Subfluxo inexistente ou ilegível: o motor transforma o null em erro de fluxo
                // com a mensagem certa, que é mais útil que propagar a exceção daqui.
                log.warn("[chatbot-workflow-manager] subfluxo '{}' não pôde ser carregado: {}", id, e.getMessage());
                return null;
            }
        });
        return new EngineContext(resolver, actionExecutor);
    }

    /**
     * Cria uma conversa e roda o primeiro turno (até o fluxo pedir entrada ou terminar).
     *
     * @param channel       identificador do canal — "ui" no chat de teste do painel.
     * @param channelUserId identidade do interlocutor dentro do canal; pode ser null.
     */
    public View start(String workflowId, String channel, String channelUserId) {
        Outcome outcome = startInternal(workflowId, channel, channelUserId);
        return toView(outcome.conversation(), outcome.result().error());
    }

    private Outcome startInternal(String workflowId, String channel, String channelUserId) {
        schema.ensureReady();
        Workflow workflow = workflowService.load(workflowId);

        Conversation conversation = new Conversation(
                UUID.randomUUID().toString(), workflowId,
                channel == null || channel.isBlank() ? "ui" : channel,
                channelUserId,
                ConversationState.initial(),
                Instant.now(), Instant.now());

        EngineResult result = engine.advance(workflow, withSystemVariables(conversation),
                Event.start(), contextFor(workflow));
        conversation = conversation.withState(result.state());

        repository.insert(conversation);
        persistOutbound(conversation.id(), result);

        log.info("[chatbot-workflow-manager] conversa iniciada | id: {} | workflow: {} | canal: {}",
                conversation.id(), workflowId, conversation.channel());
        return new Outcome(conversation, result);
    }

    /**
     * Processa uma mensagem do usuário em uma conversa existente.
     *
     * A mensagem de entrada é gravada <b>antes</b> de rodar o motor: se o turno explodir, o
     * histórico ainda mostra o que o usuário mandou, que é justamente o que se precisa para
     * reproduzir o problema.
     */
    public View handleUserMessage(String conversationId, String text) {
        schema.ensureReady();
        Conversation conversation = repository.findById(conversationId);
        if (conversation == null) {
            throw new ConversationNotFoundException("Conversa '" + conversationId + "' não encontrada.");
        }
        Outcome outcome = messageInternal(conversation, text);
        return toView(outcome.conversation(), outcome.result().error());
    }

    private Outcome messageInternal(Conversation conversation, String text) {
        repository.appendEvent(conversation.id(), "IN", Event.Type.USER_MESSAGE.name(), text);

        Workflow workflow = workflowService.load(conversation.workflowId());
        EngineResult result = engine.advance(workflow, withSystemVariables(conversation),
                Event.userMessage(text), contextFor(workflow));

        Conversation updated = conversation.withState(result.state());
        repository.update(updated);
        persistOutbound(updated.id(), result);

        if (result.hasError()) {
            log.warn("[chatbot-workflow-manager] conversa em erro | id: {} | motivo: {}", updated.id(), result.error());
        }
        return new Outcome(updated, result);
    }

    /**
     * A porta dos adapters de canal: entrega uma mensagem sem que o chamador saiba se já existe
     * conversa em andamento.
     *
     * O canal externo não tem onde guardar um {@code conversationId} — o que ele conhece é quem
     * mandou a mensagem. A chave aqui é {@code (workflowId, channel, channelUserId)}, e é este
     * método que decide entre retomar e recomeçar:
     *
     * <ul>
     *   <li><b>Nunca conversou</b> → inicia. O texto recebido <b>não</b> é consumido como resposta:
     *       o fluxo ainda não perguntou nada, então ele serve só de gatilho e o usuário recebe a
     *       saudação e a primeira pergunta.</li>
     *   <li><b>Conversa em andamento</b> → entrega o texto como resposta, retomando exatamente do
     *       nó onde parou, inclusive dentro de subfluxo.</li>
     *   <li><b>Conversa encerrada ou em erro</b> → inicia uma nova. Sem isso, quem terminou um
     *       atendimento receberia "esta conversa já foi encerrada" para sempre, sem nenhum jeito de
     *       recomeçar pelo próprio canal.</li>
     * </ul>
     *
     * @return só as mensagens <b>deste turno</b> — o adapter reenviaria o diálogo inteiro se
     *         recebesse o histórico, que é o que {@link #get(String)} devolve para a UI.
     */
    public Turn resume(String workflowId, String channel, String channelUserId, String text) {
        schema.ensureReady();

        String resolvedChannel = (channel == null || channel.isBlank()) ? "external" : channel;
        Conversation existing = repository.findLatestByChannel(workflowId, resolvedChannel, channelUserId);

        boolean startingNew = existing == null || existing.state().status().isTerminal();
        Outcome outcome = startingNew
                ? startInternal(workflowId, resolvedChannel, channelUserId)
                : messageInternal(existing, text);

        return new Turn(
                outcome.conversation().id(),
                outcome.conversation().state().status().name(),
                outboundTexts(outcome.result()),
                outcome.conversation().state().variables(),
                startingNew,
                outcome.result().error());
    }

    /** @return retrato atual da conversa, sem executar nada. */
    public View get(String conversationId) {
        schema.ensureReady();
        Conversation conversation = repository.findById(conversationId);
        if (conversation == null) {
            throw new ConversationNotFoundException("Conversa '" + conversationId + "' não encontrada.");
        }
        return toView(conversation, null);
    }

    public int count() {
        return repository.count();
    }

    // ─── Internos ─────────────────────────────────────────────────────────────────

    /**
     * Grava no histórico o que o motor decidiu enviar.
     *
     * Só SEND_MESSAGE vira linha de diálogo: WAIT_INPUT e END são transições de estado, já
     * refletidas no status da conversa, e apareceriam como mensagens vazias no chat.
     *
     * O turno devolvido ao chamador carrega o anexo inteiro; o histórico guarda só a menção. É por
     * isso que o painel, ao reabrir uma conversa antiga, mostra "[documento: x.pdf]" no lugar do
     * arquivo — o arquivo foi entregue ao canal na hora, e o histórico é registro, não repositório.
     */
    /**
     * Puts the runtime-provided variables into the state before every turn.
     *
     * Applied on each turn, not only at creation, for two reasons. It self-heals conversations
     * started before these variables existed — they would otherwise run forever without them. And
     * it makes the values authoritative: whatever is stored in the conversation row wins over
     * whatever ended up in the variables map, so a flow can never drift from the real channel.
     *
     * <p>The START event resets the state, so seeding also has to happen after the engine call
     * path picks it up — which is why this wraps the state handed to {@code advance()} rather than
     * being written once at insert time.
     */
    private ConversationState withSystemVariables(Conversation conversation) {
        return conversation.state()
                .withVariable(ConversationState.VAR_CHANNEL, conversation.channel())
                .withVariable(ConversationState.VAR_USER_ID,
                        conversation.channelUserId() == null ? "" : conversation.channelUserId());
    }

    /** Os textos que o fluxo produziu neste turno, na ordem — a mesma regra do persistOutbound. */
    private List<String> outboundTexts(EngineResult result) {
        List<String> texts = new ArrayList<>();
        for (Action action : result.actions()) {
            if (action.type() == Action.Type.SEND_MESSAGE) {
                texts.add(action.text());
            }
        }
        return texts;
    }

    private void persistOutbound(String conversationId, EngineResult result) {
        for (Action action : result.actions()) {
            if (action.type() == Action.Type.SEND_MESSAGE) {
                // O conteúdo de um anexo não entra no histórico — só a menção. Ver
                // DocumentLibrary.stripForHistory.
                repository.appendEvent(conversationId, "OUT", action.type().name(),
                        DocumentLibrary.stripForHistory(action.text()));
            }
        }
        if (result.hasError()) {
            repository.appendEvent(conversationId, "OUT", "ERROR", result.error());
        }
    }

    private View toView(Conversation conversation, String error) {
        List<Message> messages = new ArrayList<>();
        for (ConversationRepository.EventRow event : repository.findEvents(conversation.id())) {
            // Eventos de ERROR ficam no histórico para auditoria, mas não são fala de ninguém —
            // o motivo do erro vai no campo `error` da resposta, que a UI mostra em destaque.
            if ("ERROR".equals(event.type())) continue;
            messages.add(new Message("IN".equals(event.direction()) ? "user" : "bot",
                                     event.payload(), event.createdAt()));
        }
        return new View(
                conversation.id(),
                conversation.workflowId(),
                conversation.state().status().name(),
                conversation.state().currentNodeId(),
                conversation.state().variables(),
                messages,
                error);
    }

    public static class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(String message) { super(message); }
    }
}
