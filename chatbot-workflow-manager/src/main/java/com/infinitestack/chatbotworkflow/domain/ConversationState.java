package com.infinitestack.chatbotworkflow.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parte da conversa que o motor lê e reescreve: em que fluxo e nó ela parou, em que estado, o que
 * já sabe, e — quando está dentro de um subfluxo — para onde voltar.
 *
 * Separado de {@link Conversation} de propósito: o motor é uma função pura de
 * (workflow, state, event) para (novo state, ações), sem tocar em identidade, canal, timestamps nem
 * banco. Isso é o que torna o motor testável sem infraestrutura.
 *
 * @param currentWorkflowId fluxo a que {@code currentNodeId} pertence. Igual ao fluxo da conversa
 *                          fora de subfluxo; dentro de um, é o id do filho.
 * @param variables         valores do <b>escopo atual</b>. Ao entrar num subfluxo, o escopo do pai
 *                          é guardado no frame e o filho começa só com o que foi declarado em
 *                          {@code input}.
 * @param callStack         frames de retorno, do mais antigo ao mais recente. Vazio na raiz.
 */
public record ConversationState(
        ConversationStatus status,
        String currentWorkflowId,
        String currentNodeId,
        Map<String, String> variables,
        List<Frame> callStack) {

    /**
     * Um retorno pendente de subfluxo.
     *
     * @param callerVariables escopo do chamador, restaurado quando o filho termina. Guardar o mapa
     *                        inteiro (em vez de só as diferenças) é o que garante que o filho não
     *                        consiga alterar variável do pai que não esteja em {@code outputs}.
     * @param outputs         variáveis do filho copiadas de volta ao pai no retorno.
     */
    public record Frame(String workflowId, String returnNodeId,
                        Map<String, String> callerVariables, List<String> outputs) {

        public Frame {
            callerVariables = (callerVariables == null) ? Map.of() : Map.copyOf(callerVariables);
            outputs = (outputs == null) ? List.of() : List.copyOf(outputs);
        }
    }

    public ConversationState {
        variables = (variables == null) ? Map.of() : Map.copyOf(variables);
        callStack = (callStack == null) ? List.of() : List.copyOf(callStack);
    }

    /**
     * Prefix reserved for variables the runtime sets on its own.
     *
     * A flow cannot write to a name starting with it — the validator rejects that. Without the
     * reservation, an INPUT storing into {@code is_channel} would silently shadow the real channel,
     * and every branch depending on it would take the wrong path with no error anywhere.
     */
    public static final String SYSTEM_PREFIX = "is_";

    /** Channel the message came from: whatsapp, telegram, teams, insights or ui. */
    public static final String VAR_CHANNEL = SYSTEM_PREFIX + "channel";

    /** The speaker's identity inside the channel, as the adapter reported it. */
    public static final String VAR_USER_ID = SYSTEM_PREFIX + "user_id";

    /**
     * The speaker's identity as the channel itself knows it — the raw WhatsApp JID
     * ({@code 5511999999999@s.whatsapp.net}), the Telegram chat id.
     *
     * Separate from {@link #VAR_USER_ID} because they answer different questions: that one is who
     * the person is inside InfiniteStack and is what keeps a single conversation per person; this
     * one is what a flow needs when it has to act on the channel — echo the number back, look a
     * customer up by phone.
     */
    public static final String VAR_CHANNEL_USER = SYSTEM_PREFIX + "channel_user";

    /** Just the digits of a WhatsApp JID — the usable phone number. Empty on other channels. */
    public static final String VAR_PHONE = SYSTEM_PREFIX + "phone";

    /**
     * Display name the channel reports (the WhatsApp push name).
     *
     * <p>It is <b>text the contact chose for themselves</b>, so it is fine to greet with and wrong
     * to trust: never branch on it to decide access, and never treat it as an identity.
     */
    public static final String VAR_CHANNEL_USER_NAME = SYSTEM_PREFIX + "channel_user_name";

    public static boolean isSystemVariable(String name) {
        return name != null && name.startsWith(SYSTEM_PREFIX);
    }

    /** Estado inicial de uma conversa que ainda não executou nenhum nó. */
    public static ConversationState initial() {
        return new ConversationState(ConversationStatus.RUNNING, null, null, Map.of(), List.of());
    }

    public ConversationState withStatus(ConversationStatus newStatus) {
        return new ConversationState(newStatus, currentWorkflowId, currentNodeId, variables, callStack);
    }

    public ConversationState at(String workflowId, String nodeId) {
        return new ConversationState(status, workflowId, nodeId, variables, callStack);
    }

    /** Move dentro do mesmo fluxo — o caso comum, em todo {@code next}. */
    public ConversationState at(String nodeId) {
        return at(currentWorkflowId, nodeId);
    }

    public ConversationState withVariable(String name, String value) {
        Map<String, String> merged = new LinkedHashMap<>(variables);
        merged.put(name, value == null ? "" : value);
        return new ConversationState(status, currentWorkflowId, currentNodeId, merged, callStack);
    }

    public ConversationState withVariables(Map<String, String> newValues) {
        if (newValues == null || newValues.isEmpty()) return this;
        Map<String, String> merged = new LinkedHashMap<>(variables);
        newValues.forEach((key, value) -> merged.put(key, value == null ? "" : value));
        return new ConversationState(status, currentWorkflowId, currentNodeId, merged, callStack);
    }

    /** Substitui o escopo inteiro — usado ao entrar e ao sair de um subfluxo. */
    public ConversationState withScope(Map<String, String> scope) {
        return new ConversationState(status, currentWorkflowId, currentNodeId,
                scope == null ? Map.of() : scope, callStack);
    }

    public ConversationState pushFrame(Frame frame) {
        List<Frame> stack = new ArrayList<>(callStack);
        stack.add(frame);
        return new ConversationState(status, currentWorkflowId, currentNodeId, variables, stack);
    }

    public ConversationState popFrame() {
        if (callStack.isEmpty()) return this;
        List<Frame> stack = new ArrayList<>(callStack);
        stack.remove(stack.size() - 1);
        return new ConversationState(status, currentWorkflowId, currentNodeId, variables, stack);
    }

    /** @return null na raiz. */
    public Frame topFrame() {
        return callStack.isEmpty() ? null : callStack.get(callStack.size() - 1);
    }

    public int depth() {
        return callStack.size();
    }

    public String variable(String name) {
        return variables.getOrDefault(name, "");
    }
}
