package com.infinitestack.chatapp.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.infinitestack.chatapp.domain.Action;
import com.infinitestack.chatapp.domain.Conversation;
import com.infinitestack.chatapp.domain.ConversationState;
import com.infinitestack.chatapp.domain.EngineResult;
import com.infinitestack.chatapp.domain.Event;
import com.infinitestack.chatapp.domain.Workflow;
import com.infinitestack.chatapp.engine.ActionExecutor;
import com.infinitestack.chatapp.engine.EngineContext;
import com.infinitestack.chatapp.engine.WorkflowEngine;
import com.infinitestack.chatapp.engine.WorkflowResolver;
import com.infinitestack.chatapp.repository.ConversationRepository;
import com.infinitestack.chatapp.repository.SchemaInitializer;

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

    /** Retrato completo de uma conversa: estado + histórico. É o que a API devolve em todo turno. */
    public record View(String conversationId, String workflowId, String status, String currentNodeId,
                       Map<String, String> variables, List<Message> messages, String error) {}

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
                log.warn("[chat-app] subfluxo '{}' não pôde ser carregado: {}", id, e.getMessage());
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
        schema.ensureReady();
        Workflow workflow = workflowService.load(workflowId);

        Conversation conversation = new Conversation(
                UUID.randomUUID().toString(), workflowId,
                channel == null || channel.isBlank() ? "ui" : channel,
                channelUserId,
                ConversationState.initial(),
                Instant.now(), Instant.now());

        EngineResult result = engine.advance(workflow, conversation.state(), Event.start(), contextFor(workflow));
        conversation = conversation.withState(result.state());

        repository.insert(conversation);
        persistOutbound(conversation.id(), result);

        log.info("[chat-app] conversa iniciada | id: {} | workflow: {} | canal: {}",
                conversation.id(), workflowId, conversation.channel());
        return toView(conversation, result.error());
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

        repository.appendEvent(conversation.id(), "IN", Event.Type.USER_MESSAGE.name(), text);

        Workflow workflow = workflowService.load(conversation.workflowId());
        EngineResult result = engine.advance(workflow, conversation.state(), Event.userMessage(text), contextFor(workflow));

        conversation = conversation.withState(result.state());
        repository.update(conversation);
        persistOutbound(conversation.id(), result);

        if (result.hasError()) {
            log.warn("[chat-app] conversa em erro | id: {} | motivo: {}", conversation.id(), result.error());
        }
        return toView(conversation, result.error());
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
     */
    private void persistOutbound(String conversationId, EngineResult result) {
        for (Action action : result.actions()) {
            if (action.type() == Action.Type.SEND_MESSAGE) {
                repository.appendEvent(conversationId, "OUT", action.type().name(), action.text());
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
