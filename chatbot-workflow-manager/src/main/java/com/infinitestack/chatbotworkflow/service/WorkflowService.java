package com.infinitestack.chatbotworkflow.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.infinitestack.chatbotworkflow.domain.Workflow;
import com.infinitestack.chatbotworkflow.engine.WorkflowParser;
import com.infinitestack.chatbotworkflow.engine.WorkflowValidator;
import com.infinitestack.chatbotworkflow.repository.SchemaInitializer;
import com.infinitestack.chatbotworkflow.repository.WorkflowRepository;

/**
 * Regras de negócio dos fluxos: validar antes de gravar, e carregar/parsear na hora de executar.
 *
 * A validação acontece na gravação (e não só na execução) porque um fluxo quebrado só se manifesta
 * quando alguém já está conversando com ele.
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    /** @param saved false quando havia erro — nesse caso nada foi gravado. */
    public record SaveResult(boolean saved, String id, List<String> errors, List<String> warnings) {}

    private final WorkflowRepository repository;
    private final WorkflowParser parser;
    private final WorkflowValidator validator;
    private final SchemaInitializer schema;

    public WorkflowService(WorkflowRepository repository,
                           WorkflowParser parser,
                           WorkflowValidator validator,
                           SchemaInitializer schema) {
        this.repository = repository;
        this.parser = parser;
        this.validator = validator;
        this.schema = schema;
    }

    public List<WorkflowRepository.Summary> list() {
        schema.ensureReady();
        return repository.findAllSummaries();
    }

    /** @return null se não existir. */
    public WorkflowRepository.Row get(String id) {
        schema.ensureReady();
        return repository.findById(id);
    }

    /** Valida sem gravar — usado pelo botão "Validar" do editor. */
    public SaveResult validate(String json) {
        try {
            Workflow workflow = parser.parse(json);
            WorkflowValidator.Result result = validator.validate(workflow);
            return new SaveResult(false, workflow.id(), result.errors(), result.warnings());
        } catch (WorkflowParser.WorkflowParseException e) {
            return new SaveResult(false, null, List.of(e.getMessage()), List.of());
        }
    }

    /**
     * Valida e grava. Avisos não impedem a gravação; erros sim.
     *
     * @param pathId id vindo da URL. Se divergir do id no corpo, é erro — corrigir silenciosamente
     *               um dos dois faria o autor gravar em cima de um fluxo que ele não estava editando.
     */
    public SaveResult save(String pathId, String json) {
        schema.ensureReady();
        Workflow workflow;
        try {
            workflow = parser.parse(json);
        } catch (WorkflowParser.WorkflowParseException e) {
            return new SaveResult(false, null, List.of(e.getMessage()), List.of());
        }

        if (pathId != null && workflow.id() != null && !pathId.equals(workflow.id())) {
            return new SaveResult(false, workflow.id(),
                    List.of("O id no corpo ('" + workflow.id() + "') não confere com o id da URL ('" + pathId + "')."),
                    List.of());
        }

        WorkflowValidator.Result result = validator.validate(workflow);
        if (!result.valid()) {
            return new SaveResult(false, workflow.id(), result.errors(), result.warnings());
        }

        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(missingSubflowWarnings(workflow));

        repository.save(workflow.id(), workflow.name(), json);
        log.info("[chatbot-workflow-manager] workflow gravado | id: {} | nós: {}", workflow.id(), workflow.nodes().size());
        return new SaveResult(true, workflow.id(), List.of(), warnings);
    }

    /**
     * Subfluxo referenciado que ainda não existe vira <b>aviso</b>, não erro.
     *
     * Bloquear seria impor uma ordem de criação: quem desenha o fluxo pai antes do filho — o
     * caminho natural — não conseguiria salvar o rascunho. O erro real aparece na execução, com a
     * mensagem "Subfluxo 'x' não encontrado", e aqui o autor já foi avisado.
     */
    private List<String> missingSubflowWarnings(Workflow workflow) {
        List<String> warnings = new ArrayList<>();
        for (com.infinitestack.chatbotworkflow.domain.Node node : workflow.nodes()) {
            if (node.type() != com.infinitestack.chatbotworkflow.domain.NodeType.CALL_WORKFLOW) continue;
            String target = node.config("workflow");
            if (target == null || target.equals(workflow.id())) continue;
            if (repository.findById(target) == null) {
                warnings.add("Nó '" + node.id() + "' chama o subfluxo '" + target
                        + "', que ainda não existe.");
            }
        }
        return warnings;
    }

    public boolean delete(String id) {
        schema.ensureReady();
        boolean removed = repository.delete(id) > 0;
        if (removed) log.info("[chatbot-workflow-manager] workflow removido | id: {}", id);
        return removed;
    }

    /**
     * Carrega e parseia um fluxo para execução.
     *
     * @throws WorkflowNotFoundException se não existir
     * @throws WorkflowParser.WorkflowParseException se o JSON gravado não parsear (só acontece se
     *         alguém editar a linha direto no banco, contornando a validação da gravação)
     */
    public Workflow load(String id) {
        schema.ensureReady();
        WorkflowRepository.Row row = repository.findById(id);
        if (row == null) {
            throw new WorkflowNotFoundException("Workflow '" + id + "' não encontrado.");
        }
        return parser.parse(row.definition());
    }

    /**
     * Insere o fluxo de demonstração quando ainda não há nenhum cadastrado.
     *
     * Sem isso o painel abre vazio e o primeiro contato com o app é uma tela em branco sem nada
     * para executar — o autor teria que escrever um workflow do zero antes de ver o motor rodar.
     */
    public void seedDemoIfEmpty() {
        if (repository.count() > 0) return;
        try {
            String json = new String(new ClassPathResource("demo-workflow.json").getInputStream().readAllBytes(),
                                     StandardCharsets.UTF_8);
            SaveResult result = save(null, json);
            if (result.saved()) {
                log.info("[chatbot-workflow-manager] workflow de demonstração inserido | id: {}", result.id());
            } else {
                log.warn("[chatbot-workflow-manager] workflow de demonstração inválido — não inserido: {}", result.errors());
            }
        } catch (Exception e) {
            log.warn("[chatbot-workflow-manager] falha ao inserir o workflow de demonstração: {}", e.getMessage());
        }
    }

    public static class WorkflowNotFoundException extends RuntimeException {
        public WorkflowNotFoundException(String message) { super(message); }
    }
}
