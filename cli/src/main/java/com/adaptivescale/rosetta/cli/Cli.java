package com.adaptivescale.rosetta.cli;

import com.adaptivescale.rosetta.cli.model.Config;
import com.adaptivescale.rosetta.cli.outputs.StringOutput;
import com.adaptivescale.rosetta.cli.outputs.YamlModelOutput;
import com.adaptivescale.rosetta.common.models.Database;
import com.adaptivescale.rosetta.common.models.input.Target;
import com.adaptivescale.rosetta.ddl.DDL;
import com.adaptivescale.rosetta.ddl.DDLFactory;
import com.adaptivescale.rosetta.translator.Translator;
import com.adaptivescale.rosetta.translator.TranslatorFactory;
import com.adataptivescale.rosetta.source.core.SourceGeneratorFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.Callable;

import static com.adaptivescale.rosetta.cli.Constants.*;

@Slf4j
@CommandLine.Command(name = "cli", mixinStandardHelpOptions = true, version = "rosetta 0.0.1",
        description = "Declarative Database Management - DDL Transpiler"
)
class Cli implements Callable<Void> {
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-c", "--config"},
            converter = ConfigYmlConverter.class,
            defaultValue = CONFIG_NAME,
            description = "YAML config file. If none is supplied it will use main.conf in the current directory if it exists.")
    private Config config;

    @Override
    public Void call() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    @CommandLine.Command(name = "extract", description = "Extract schema chosen from connection config.")
    private void extract(@CommandLine.Option(names = {"-s", "--source"}, required = true) String sourceName,
                         @CommandLine.Option(names = {"-t", "--convert-to"}) Optional<String> targetName,
                         @CommandLine.Option(names = {"-o", "--output-dir"},
                                 defaultValue = "./") Optional<Path> outputDirectory
    ) throws Exception {
        requireConfig(config);
        Optional<Target> source = config.getTarget(sourceName);
        if (!source.isPresent()) {
            throw new RuntimeException("Can not find source with name: " + sourceName + " configured in config.");
        }

        if (!outputDirectory.isPresent()) {
            throw new RuntimeException("Output directory is null");
        }

        Database result = SourceGeneratorFactory.sourceGenerator(source.get()).generate(source.get());
        YamlModelOutput yamlInputModel = new YamlModelOutput(MODEL_INPUT_NAME, outputDirectory.get());
        yamlInputModel.write(result);
        log.info("Successfully written input database yaml ({}).", yamlInputModel.getFilePath());

        if (!targetName.isPresent()) {
            return;
        }

        Optional<Target> target = config.getTarget(targetName.get());
        if (!target.isPresent()) {
            throw new RuntimeException("Can not find target with name: " + targetName.orElse(null) + " configured in config.");
        }

        Translator<Database, Database> translator = TranslatorFactory.translator(source.get().getDbType(),
                target.get().getDbType());
        result = translator.translate(result);

        YamlModelOutput yamlOutputModel = new YamlModelOutput(MODEL_OUTPUT_NAME, outputDirectory.get());
        yamlOutputModel.write(result);
        log.info("Successfully written output database yaml ({}).", yamlOutputModel.getFilePath());
    }

    @CommandLine.Command(name = "compile", description = "Generate DDL for target Database [bigquery, snowflake, …]")
    private void compile(@CommandLine.Option(names = {"-t", "--target"}, required = true) String targetName,
                         @CommandLine.Option(names = {"-i", "--input-dir"}, defaultValue = "./") Optional<Path> inputDirectory
    ) throws Exception {
        requireConfig(config);
        Optional<Target> target = config.getTarget(targetName);
        if (!target.isPresent()) {
            throw new RuntimeException("Can not find target with name: " + targetName + " configured in config.");
        }

        if (!inputDirectory.isPresent()) {
            throw new RuntimeException("Input directory is null");
        }

        Path modelDirectory = inputDirectory.get().resolve(MODEL_DIRECTORY_NAME);
        if (!Files.exists(modelDirectory)) {
            throw new RuntimeException("Can not find model directory");
        }

        Path inputDatabasePath = modelDirectory.resolve(MODEL_INPUT_NAME);
        if (!Files.exists(inputDatabasePath)) {
            throw new RuntimeException("Can not locate " + MODEL_INPUT_NAME + " in directory: " + modelDirectory.toAbsolutePath());
        }

        Database input = new ObjectMapper(new YAMLFactory()).readValue(inputDatabasePath.toFile(), Database.class);
        Translator<Database, Database> translator = TranslatorFactory.translator(input.getDatabaseType(), target.get().getDbType());
        Database translatedInput = translator.translate(input);

        new YamlModelOutput(MODEL_OUTPUT_NAME, inputDirectory.get()).write(translatedInput);

        DDL ddl = DDLFactory.ddlForDatabaseType(target.get().getDbType());
        String ddlDataBase = ddl.createDataBase(translatedInput);
        StringOutput stringOutput = new StringOutput("ddl.sql", modelDirectory);
        stringOutput.write(ddlDataBase);
        log.info("Successfully written ddl ({}).", stringOutput.getFilePath());
    }

    @CommandLine.Command(name = "init", description = "Creates a sample config (main.conf).")
    private void init() throws IOException {
        Path fileName = Paths.get(CONFIG_NAME);
        InputStream resourceAsStream = getClass().getResourceAsStream("/" + TEMPLATE_CONFIG_NAME);
        Files.copy(resourceAsStream, fileName);
        log.info("Successfully created sample config ({}).", CONFIG_NAME);
    }

    private void requireConfig(Config config) {
        if (config == null) {
            throw new RuntimeException("Config file is required.");
        }
    }
}
