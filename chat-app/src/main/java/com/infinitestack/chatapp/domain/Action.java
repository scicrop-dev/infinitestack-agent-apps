package com.infinitestack.chatapp.domain;

import java.util.Map;

/**
 * O que sai do motor (docs/006-actions.md). Uma ação é uma intenção, não um efeito: quem executa
 * é o adapter do canal ou o Action Executor. Isso é o que permite o mesmo fluxo rodar no chat de
 * teste do painel hoje e em WhatsApp/Telegram depois, sem tocar no motor.
 *
 * @param details metadados para rastro e para o adapter. Em SEND_MESSAGE fica vazio; em DB_QUERY e
 *                HTTP_REQUEST carrega o que foi executado e o resultado resumido, que é o que
 *                permite auditar depois por que um fluxo tomou o ramo que tomou.
 */
public record Action(Type type, String text, Map<String, String> details) {

    public enum Type { SEND_MESSAGE, WAIT_INPUT, END, DB_QUERY, HTTP_REQUEST }

    public Action {
        details = (details == null) ? Map.of() : Map.copyOf(details);
    }

    public static Action sendMessage(String text) {
        return new Action(Type.SEND_MESSAGE, text, Map.of());
    }

    public static Action waitInput() {
        return new Action(Type.WAIT_INPUT, "", Map.of());
    }

    public static Action end() {
        return new Action(Type.END, "", Map.of());
    }

    public static Action effect(Type type, String summary, Map<String, String> details) {
        return new Action(type, summary, details);
    }
}
