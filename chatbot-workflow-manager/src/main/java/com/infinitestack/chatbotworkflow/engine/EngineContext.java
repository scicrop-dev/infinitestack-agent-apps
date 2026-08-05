package com.infinitestack.chatbotworkflow.engine;

import com.infinitestack.chatbotworkflow.domain.Workflow;

/**
 * As dependências externas de um turno, agrupadas para não virarem quatro parâmetros soltos em
 * {@code advance()} — e para que acrescentar uma quinta no futuro não mude a assinatura do motor.
 *
 * @param resolver resolve subfluxos por id
 * @param executor executa DB_QUERY e HTTP_REQUEST
 */
public record EngineContext(WorkflowResolver resolver, ActionExecutor executor) {

    public EngineContext {
        resolver = (resolver == null) ? id -> null : resolver;
        executor = (executor == null) ? ActionExecutor.DENY_ALL : executor;
    }

    /** Contexto de fluxo único, sem efeitos — o padrão de quem só usa os nós conversacionais. */
    public static EngineContext standalone(Workflow workflow) {
        return new EngineContext(WorkflowResolver.of(workflow), ActionExecutor.DENY_ALL);
    }
}
