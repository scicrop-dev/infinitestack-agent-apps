package com.infinitestack.chatbotworkflow.domain;

/**
 * O que entra no motor: ou a conversa está começando, ou o usuário respondeu algo.
 *
 * @param type tipo do evento
 * @param text texto do usuário — vazio em START
 */
public record Event(Type type, String text) {

    public enum Type { START, USER_MESSAGE }

    public static Event start() {
        return new Event(Type.START, "");
    }

    public static Event userMessage(String text) {
        return new Event(Type.USER_MESSAGE, text == null ? "" : text);
    }
}
