package com.infinitestack.chatapp.domain;

import java.util.List;

/**
 * Saída de um turno do motor: o novo estado da conversa e as ações a executar, nesta ordem.
 *
 * @param error mensagem do que deu errado quando o status é ERROR; null caso contrário. Fica no
 *              resultado (e não numa exceção) porque erro de fluxo é dado a ser mostrado ao autor
 *              do workflow, não falha do processo.
 */
public record EngineResult(ConversationState state, List<Action> actions, String error) {

    public EngineResult {
        actions = List.copyOf(actions);
    }

    public boolean hasError() {
        return error != null;
    }
}
