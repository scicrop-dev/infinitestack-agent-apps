package com.infinitestack.chatapp.engine;

import com.infinitestack.chatapp.domain.Workflow;

/**
 * Resolve um fluxo por id, para o motor poder entrar em subfluxos sem conhecer o repositório.
 *
 * É o mesmo padrão do {@link ActionExecutor}: a dependência entra como parâmetro da chamada, o motor
 * continua puro, e o teste passa um mapa em memória.
 */
@FunctionalInterface
public interface WorkflowResolver {

    /** @return null se o fluxo não existir — o motor trata como erro de fluxo, não exceção. */
    Workflow resolve(String workflowId);

    /** Resolvedor que só conhece um fluxo: o caso de quem não usa subfluxo. */
    static WorkflowResolver of(Workflow workflow) {
        return id -> (workflow != null && workflow.id() != null && workflow.id().equals(id)) ? workflow : null;
    }
}
