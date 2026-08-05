package com.infinitestack.chatapp.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Valida todo fluxo de exemplo em docs/examples.
 *
 * Exemplo em documentação apodrece calado: alguém muda o nome de um campo, o exemplo continua lá
 * parecendo correto, e quem copia dele descobre o problema depois. Este teste faz a documentação
 * falhar junto com o código.
 *
 * Exemplos marcados com {@code "_comment"} descrevem funcionalidade ainda não suportada (subfluxos)
 * e são deliberadamente pulados — são especificação de alvo, não fluxo executável.
 */
class DocsExamplesTest {

    private static final Path EXAMPLES = Path.of("docs", "examples");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowParser parser = new WorkflowParser(objectMapper);
    private final WorkflowValidator validator = new WorkflowValidator();

    @TestFactory
    Stream<DynamicTest> todoExemploExecutavelEValido() throws IOException {
        // Roda a partir da raiz do módulo; se alguém invocar de outro diretório, pular é melhor
        // que reprovar por um motivo que não é do código.
        Assumptions.assumeTrue(Files.isDirectory(EXAMPLES), "docs/examples não encontrado");

        try (Stream<Path> files = Files.list(EXAMPLES)) {
            List<Path> examples = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertTrue(!examples.isEmpty(), "nenhum exemplo encontrado em docs/examples");

            return examples.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
                String json = Files.readString(path);
                if (objectMapper.readTree(json).has("_comment")) return;  // alvo futuro, não executável

                WorkflowValidator.Result result = validator.validate(parser.parse(json));
                assertTrue(result.valid(), path.getFileName() + " inválido: " + result.errors());
            }));
        }
    }
}
