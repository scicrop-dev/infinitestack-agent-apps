package com.infinitestack.chatbotworkflow.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * As barreiras da biblioteca de documentos.
 *
 * O que justifica cada teste aqui: o nome do arquivo pode ser interpolado de uma variável da
 * conversa, então ele pode derivar do que o usuário digitou no chat. Cada caso abaixo é uma forma
 * de sair do diretório usando esse caminho.
 */
class DocumentLibraryTest {

    @TempDir Path root;

    private Path documents;
    private DocumentLibrary library;

    @BeforeEach
    void setUp() throws Exception {
        documents = Files.createDirectory(root.resolve("documents"));
        Files.writeString(documents.resolve("tabela-precos.pdf"), "%PDF-1.4 conteudo de teste");
        Files.writeString(documents.resolve("manual.txt"), "manual");

        library = new DocumentLibrary();
        ReflectionTestUtils.setField(library, "configuredDir", documents.toString());
        ReflectionTestUtils.setField(library, "dataRoot", root.toString());
        ReflectionTestUtils.setField(library, "maxBytes", 1024L);
    }

    // ─── Caminho feliz ────────────────────────────────────────────────────────────

    @Test
    void carregaOArquivoComMimeDaExtensao() {
        DocumentLibrary.Document document = library.load("tabela-precos.pdf", Map.of());

        assertEquals("tabela-precos.pdf", document.fileName());
        assertEquals("application/pdf", document.mimeType());
        assertEquals("%PDF-1.4 conteudo de teste",
                new String(Base64.getDecoder().decode(document.base64()), StandardCharsets.UTF_8));
    }

    @Test
    void extensaoDesconhecidaViraBinarioGenericoEmVezDeFalhar() throws Exception {
        Files.writeString(documents.resolve("dados.qualquer"), "x");
        assertEquals("application/octet-stream", library.load("dados.qualquer", Map.of()).mimeType());
    }

    @Test
    void geraMarkdownDeLinkENaoDeImagem() {
        // A ausência do "!" é o que faz o backend enviar como documento, e não tentar decodificar
        // como imagem — trocar isso quebra a integração inteira em silêncio.
        String markdown = library.load("manual.txt", Map.of()).asMarkdownLink();

        assertTrue(markdown.startsWith("[manual.txt](data:text/plain;base64,"), markdown);
        assertTrue(!markdown.startsWith("!"), "documento não pode usar markdown de imagem");
    }

    @Test
    void listaOsArquivosDisponiveis() {
        assertEquals(List.of("manual.txt", "tabela-precos.pdf"), library.list());
    }

    // ─── Caminho ──────────────────────────────────────────────────────────────────

    @Test
    void aceitaSubpastaSobODiretorioDeDocumentos() throws Exception {
        Path manuais = Files.createDirectory(documents.resolve("manuais"));
        Files.writeString(manuais.resolve("produto-a.pdf"), "%PDF manual A");

        DocumentLibrary.Document document = library.load("manuais/produto-a.pdf", Map.of());

        assertEquals("produto-a.pdf", document.fileName());
        assertEquals("application/pdf", document.mimeType());
    }

    @Test
    void aceitaCaminhoAbsolutoForaDoDiretorio() throws Exception {
        Path fora = Files.writeString(root.resolve("relatorio.pdf"), "%PDF fora");

        DocumentLibrary.Document document = library.load(fora.toString(), Map.of());

        assertEquals("relatorio.pdf", document.fileName());
    }

    @Test
    void templateAbsolutoComVariavelResolveDentroDaPastaDoTemplate() throws Exception {
        Path relatorios = Files.createDirectory(root.resolve("relatorios"));
        Files.writeString(relatorios.resolve("2026.pdf"), "%PDF ano");

        DocumentLibrary.Document document =
                library.load(relatorios + "/{{ano}}.pdf", Map.of("ano", "2026"));

        assertEquals("2026.pdf", document.fileName());
    }

    // ─── Fronteira de confiança: template é do autor, valor é do usuário ──────────

    @Test
    void valorInterpoladoNaoPodeConterCaminho() {
        // O caso que importa: um menu deixa o usuário escolher o documento, e ele responde
        // "../../etc/passwd". O template é do autor; o valor, não.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> library.load("{{doc}}", Map.of("doc", "../../etc/passwd")));
        assertTrue(e.getMessage().contains("não pode conter caminho"), e.getMessage());
    }

    @Test
    void valorInterpoladoNaoEscapaDeTemplateAbsoluto() {
        assertThrows(IllegalArgumentException.class,
                () -> library.load("/var/reports/{{protocolo}}.pdf", Map.of("protocolo", "../../../etc/passwd")));
        assertThrows(IllegalArgumentException.class,
                () -> library.load("/var/reports/{{protocolo}}.pdf", Map.of("protocolo", "a/b")));
    }

    @Test
    void valorInterpoladoNormalContinuaFuncionando() throws Exception {
        Files.writeString(documents.resolve("produto-x.pdf"), "%PDF x");

        assertEquals("produto-x.pdf",
                library.load("{{nome}}.pdf", Map.of("nome", "produto-x")).fileName());
    }

    // ─── Allowlist opcional de raízes ────────────────────────────────────────────

    @Test
    void semAllowlistOCaminhoAbsolutoPassa() throws Exception {
        Path fora = Files.writeString(root.resolve("livre.pdf"), "%PDF livre");
        assertEquals("livre.pdf", library.load(fora.toString(), Map.of()).fileName());
    }

    @Test
    void comAllowlistLeituraForaDasRaizesEhBloqueada() throws Exception {
        Path fora = Files.writeString(root.resolve("proibido.pdf"), "%PDF proibido");
        ReflectionTestUtils.setField(library, "allowedRoots", documents.toString());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> library.load(fora.toString(), Map.of()));
        assertTrue(e.getMessage().contains("raízes permitidas"), e.getMessage());

        // Dentro da raiz listada continua funcionando.
        assertEquals("manual.txt", library.load("manual.txt", Map.of()).fileName());
    }

    @Test
    void allowlistResolveLinkSimbolicoAntesDeComparar() throws Exception {
        // Um link dentro da raiz apontando para fora seria a forma óbvia de contornar a allowlist.
        Path segredo = Files.writeString(root.resolve("segredo.pdf"), "sigiloso");
        try {
            Files.createSymbolicLink(documents.resolve("atalho.pdf"), segredo);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // filesystem sem symlink — nada a verificar
        }
        ReflectionTestUtils.setField(library, "allowedRoots", documents.toString());

        assertThrows(IllegalArgumentException.class, () -> library.load("atalho.pdf", Map.of()));
    }

    // ─── Limites ──────────────────────────────────────────────────────────────────

    @Test
    void recusaArquivoAcimaDoTeto() throws Exception {
        Files.write(documents.resolve("grande.pdf"), new byte[2048]);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> library.load("grande.pdf", Map.of()));
        assertTrue(e.getMessage().contains("limite"), e.getMessage());
    }

    @Test
    void recusaArquivoVazioEArquivoInexistente() throws Exception {
        Files.createFile(documents.resolve("vazio.pdf"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> library.load("vazio.pdf", Map.of())).getMessage().contains("vazio"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> library.load("nao-existe.pdf", Map.of())).getMessage().contains("não encontrado"));
    }

    // ─── Higiene do histórico ─────────────────────────────────────────────────────

    @Test
    void stripForHistoryTrocaOConteudoPelaMencao() {
        // Sem isto, cada anexo enviado grava seu base64 em chatbot_workflow_manager_event, numa tabela append-only
        // relida inteira toda vez que a conversa é aberta.
        String comAnexo = "Segue.\n\n" + library.load("tabela-precos.pdf", Map.of()).asMarkdownLink();

        String gravado = DocumentLibrary.stripForHistory(comAnexo);

        assertEquals("Segue.\n\n[documento: tabela-precos.pdf]", gravado);
        assertTrue(!gravado.contains("base64"));
    }

    @Test
    void stripForHistoryNaoTocaEmImagemEmbutida() {
        // Imagem usa markdown de imagem e é tratada por outro caminho; converter aqui a
        // descaracterizaria no histórico.
        String comImagem = "Veja ![grafico](data:image/png;base64,iVBORw0KGgo=)";

        assertEquals(comImagem, DocumentLibrary.stripForHistory(comImagem));
    }

    @Test
    void recusaNomeVazio() {
        assertThrows(IllegalArgumentException.class, () -> library.load("  ", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> library.load(null, Map.of()));
    }
}
