package com.infinitestack.chatbotworkflow.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the files a flow can send — price list, manual, generated report.
 *
 * <p>{@code file} accepts a full path: a bare name and a subfolder resolve against the documents
 * root, and an absolute path is honoured as written. That is deliberate — the library grows into
 * folders, and files worth sending often already live elsewhere on the server.
 *
 * <h3>Where the trust boundary actually is</h3>
 * The dangerous part was never "having a path". It is that {@code file} is interpolated, so part
 * of it can come from what the user typed in the chat. So the two halves are treated differently:
 *
 * <ul>
 *   <li><b>The template is authored</b> — written by whoever edits the flow, an ADMIN/SCICROP in
 *       the panel. It may point anywhere, absolute included.</li>
 *   <li><b>The interpolated values are not</b> — they come from the conversation. A value is
 *       rejected outright if it carries a separator or {@code ..}, so
 *       {@code "/var/reports/{{protocol}}.pdf"} stays inside {@code /var/reports} no matter what
 *       the user answers.</li>
 * </ul>
 *
 * Rejecting rather than stripping is on purpose: silently turning {@code ../../etc/passwd} into
 * {@code etcpasswd} would surface later as a confusing "file not found".
 *
 * <h3>Remaining guards</h3>
 * <ul>
 *   <li><b>Optional root allowlist</b> ({@code chatbot.documents.allowed-roots}) — empty by
 *       default, which is what makes absolute paths work out of the box. An operator who wants the
 *       old containment sets it and every read must land under one of the listed roots.</li>
 *   <li><b>Size ceiling</b> — the file travels as base64 inside the answer and each hop holds it
 *       in memory. Checked before reading, so an oversized file never costs the memory.</li>
 *   <li><b>Audit log</b> — any read resolving outside the documents root is logged, because that
 *       is the case worth being able to review afterwards.</li>
 * </ul>
 */
@Component
public class DocumentLibrary {

    private static final Logger log = LoggerFactory.getLogger(DocumentLibrary.class);

    /**
     * Tipos reconhecidos pela extensão. A lista é curta de propósito: serve para o canal exibir o
     * ícone certo e nomear o anexo. O que não estiver aqui vai como binário genérico, que o
     * WhatsApp entrega do mesmo jeito.
     */
    private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
            Map.entry("pdf",  "application/pdf"),
            Map.entry("csv",  "text/csv"),
            Map.entry("txt",  "text/plain"),
            Map.entry("json", "application/json"),
            Map.entry("xml",  "application/xml"),
            Map.entry("zip",  "application/zip"),
            Map.entry("doc",  "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls",  "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt",  "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    /** Arquivo pronto para viajar até o canal. */
    public record Document(String fileName, String mimeType, String base64, long bytes) {

        /**
         * Markdown de <b>link</b>, não de imagem: {@code [nome.pdf](data:...)}.
         *
         * A ausência do {@code !} é o que distingue documento de imagem para quem lê a resposta —
         * o backend usa exatamente isso para escolher entre enviar como imagem e enviar como
         * documento. E é semanticamente correto: um PDF é um link para um arquivo, não uma imagem
         * a exibir. De quebra, no Insights (web) o próprio navegador transforma esse link num
         * download funcionando, sem nenhum código de front.
         */
        public String asMarkdownLink() {
            return "[" + fileName + "](data:" + mimeType + ";base64," + base64 + ")";
        }
    }

    /**
     * O mesmo formato de {@link Document#asMarkdownLink()}, do lado de quem lê.
     *
     * A âncora {@code (?<!!)} distingue anexo de imagem: sem ela, o markdown de imagem também
     * casaria e uma imagem embutida viraria "[documento: …]" no histórico.
     */
    private static final Pattern DOCUMENT_LINK =
            Pattern.compile("(?<!!)\\[([^]]+)]\\(data:[^;]+;base64,[A-Za-z0-9+/=]+\\)");

    /**
     * Troca o conteúdo do anexo por uma menção legível, para gravar no histórico.
     *
     * {@code chatbot_event} existe para rastreabilidade e para a UI remontar o diálogo — nenhum
     * dos dois precisa dos bytes do arquivo. Guardá-los ali significaria megabytes de base64 por
     * mensagem numa tabela append-only que só cresce, relida inteira a cada abertura da conversa.
     * O nome do arquivo é o que dá a informação: <i>o quê</i> foi enviado, e quando.
     */
    public static String stripForHistory(String text) {
        if (text == null) return "";
        return DOCUMENT_LINK.matcher(text).replaceAll(result -> "[documento: " + result.group(1) + "]");
    }

    @Value("${chatbot.documents.dir:}")
    private String configuredDir;

    @Value("${infinitestack.plugin.data-root:./data}")
    private String dataRoot;

    @Value("${chatbot.documents.max-bytes:10485760}")
    private long maxBytes;

    /** Comma-separated roots a read must fall under. Empty (the default) means unrestricted. */
    @Value("${chatbot.documents.allowed-roots:}")
    private String allowedRoots;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    /** Diretório efetivo dos documentos, criado sob demanda. */
    public Path directory() {
        Path dir = (configuredDir == null || configuredDir.isBlank())
                ? Path.of(dataRoot, "documents")
                : Path.of(configuredDir);
        return dir.toAbsolutePath().normalize();
    }

    /**
     * Resolves and reads the file a node asked for.
     *
     * @param template  the {@code file} config, still holding its {{placeholders}}
     * @param variables conversation scope used to fill them
     * @throws IllegalArgumentException with the exact reason when the file cannot be sent
     */
    public Document load(String template, Map<String, String> variables) {
        String requested = interpolateSafely(template, variables);
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("Nome de arquivo vazio.");
        }

        Path dir = directory();
        Path candidate = Path.of(requested);
        // A relative path hangs off the documents root; an absolute one stands on its own.
        Path resolved = (candidate.isAbsolute() ? candidate : dir.resolve(candidate)).normalize();

        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Arquivo '" + requested + "' não encontrado em " + resolved + ".");
        }

        try {
            // Resolves symlinks: what gets read is what the allowlist is checked against, so a
            // link cannot be used to reach past a configured root.
            Path realFile = resolved.toRealPath();
            enforceAllowedRoots(realFile, requested);

            long size = Files.size(realFile);
            if (size == 0) {
                throw new IllegalArgumentException("Arquivo '" + requested + "' está vazio.");
            }
            if (size > maxBytes) {
                throw new IllegalArgumentException("Arquivo '" + requested + "' tem " + (size / 1024)
                        + " KB e o limite é " + (maxBytes / 1024) + " KB.");
            }

            if (!realFile.startsWith(safeRealPath(dir))) {
                log.warn("[chatbot-workflow-manager] documento lido fora do diretório de documentos: {}", realFile);
            }

            byte[] content = Files.readAllBytes(realFile);
            return new Document(realFile.getFileName().toString(), mimeTypeOf(requested),
                    Base64.getEncoder().encodeToString(content), size);

        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o arquivo '" + requested + "': " + e.getMessage());
        }
    }

    /**
     * Fills {{placeholders}} while refusing any value that would change the shape of the path.
     *
     * This is the whole trust boundary: the template may say anything, a substituted value may not
     * introduce a separator or {@code ..}. Without it, a menu that lets the user pick a document
     * would double as a way to ask for any file the process can read.
     */
    private String interpolateSafely(String template, Map<String, String> variables) {
        if (template == null) return "";
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = (variables == null) ? "" : variables.getOrDefault(name, "");
            if (value.contains("/") || value.contains("\\") || value.contains("..")) {
                throw new IllegalArgumentException("Valor de '" + name + "' não pode conter caminho: '"
                        + value + "'.");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }

    /** No roots configured means no restriction — the default, and what makes absolute paths work. */
    private void enforceAllowedRoots(Path realFile, String requested) {
        if (allowedRoots == null || allowedRoots.isBlank()) return;

        for (String root : allowedRoots.split(",")) {
            if (root.isBlank()) continue;
            Path realRoot = safeRealPath(Path.of(root.trim()).toAbsolutePath().normalize());
            if (realFile.startsWith(realRoot)) return;
        }
        log.warn("[chatbot-workflow-manager] leitura bloqueada pela allowlist de raízes: {}", realFile);
        throw new IllegalArgumentException("Arquivo '" + requested
                + "' está fora das raízes permitidas (chatbot.documents.allowed-roots).");
    }

    /** A root that does not exist yet cannot be resolved — fall back to the normalized form. */
    private Path safeRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Nomes disponíveis — usado pelo painel para o autor saber o que pode referenciar. */
    public List<String> list() {
        Path dir = directory();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[chatbot-workflow-manager] não foi possível listar {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private String mimeTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "application/octet-stream";
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME_BY_EXTENSION.getOrDefault(extension, "application/octet-stream");
    }
}
