package com.github.jrcodeza.schema.generator;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.jrcodeza.OpenApiIgnore;
import com.github.jrcodeza.schema.generator.config.OpenApiGeneratorConfig;
import com.github.jrcodeza.schema.generator.config.builder.OpenApiGeneratorConfigBuilder;
import com.github.jrcodeza.schema.generator.interceptors.OperationInterceptor;
import com.github.jrcodeza.schema.generator.interceptors.OperationParameterInterceptor;
import com.github.jrcodeza.schema.generator.interceptors.RequestBodyInterceptor;
import com.github.jrcodeza.schema.generator.interceptors.SchemaFieldInterceptor;
import com.github.jrcodeza.schema.generator.interceptors.SchemaInterceptor;
import com.github.jrcodeza.schema.generator.interceptors.examples.OperationParameterExampleInterceptor;
import com.github.jrcodeza.schema.generator.model.GenerationContext;
import com.github.jrcodeza.schema.generator.model.Header;
import com.github.jrcodeza.schema.generator.model.InheritanceInfo;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;

public class OpenAPIGenerator {

    private static Logger logger = LoggerFactory.getLogger(OpenAPIGenerator.class);

    private static final String DEFAULT_DISCRIMINATOR_NAME = "type";

    private List<String> modelPackages;
    private List<String> controllerBasePackages;

    private final ComponentSchemaTransformer componentSchemaTransformer;
    private final OperationsTransformer operationsTransformer;
    private final Info info;

    private final List<SchemaInterceptor> schemaInterceptors;
    private final List<SchemaFieldInterceptor> schemaFieldInterceptors;
    private final List<OperationParameterInterceptor> operationParameterInterceptors;
    private final List<OperationInterceptor> operationInterceptors;
    private final List<RequestBodyInterceptor> requestBodyInterceptors;

    private final List<Header> globalHeaders;

    public OpenAPIGenerator(List<String> modelPackages, List<String> controllerBasePackages, Info info) {
        this(modelPackages, controllerBasePackages, info, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public OpenAPIGenerator(List<String> modelPackages, List<String> controllerBasePackages, Info info,
                            List<SchemaInterceptor> schemaInterceptors,
                            List<SchemaFieldInterceptor> schemaFieldInterceptors,
                            List<OperationParameterInterceptor> operationParameterInterceptors,
                            List<OperationInterceptor> operationInterceptors,
                            List<RequestBodyInterceptor> requestBodyInterceptors) {
        this.modelPackages = modelPackages;
        this.controllerBasePackages = controllerBasePackages;
        this.componentSchemaTransformer = new ComponentSchemaTransformer(schemaFieldInterceptors);
        this.globalHeaders = new ArrayList<>();

        GenerationContext operationsGenerationContext = new GenerationContext(null, removeRegexFormatFromPackages(modelPackages));
        this.operationsTransformer = new OperationsTransformer(
                operationsGenerationContext, operationParameterInterceptors, operationInterceptors, requestBodyInterceptors, globalHeaders
        );

        this.info = info;
        this.schemaInterceptors = schemaInterceptors;
        this.schemaFieldInterceptors = schemaFieldInterceptors;
        this.operationParameterInterceptors = operationParameterInterceptors;
        this.operationInterceptors = operationInterceptors;
        this.requestBodyInterceptors = requestBodyInterceptors;
    }

    public OpenAPI generate() {
        return generate(OpenApiGeneratorConfigBuilder.defaultConfig().build());
    }

    public OpenAPI generate(OpenApiGeneratorConfig openApiGeneratorConfig) {
        logger.info("Starting OpenAPI generation");
        initializeExampleInterceptor(openApiGeneratorConfig);
        OpenAPI openAPI = new OpenAPI();
        openAPI.setComponents(createComponentsWrapper());
        openAPI.setPaths(createPathsWrapper());
        openAPI.setInfo(info);
        logger.info("OpenAPI generation done!");
        return openAPI;
    }

    private void initializeExampleInterceptor(OpenApiGeneratorConfig openApiGeneratorConfig) {
        if (openApiGeneratorConfig.isGenerateExamples()) {
            OperationParameterExampleInterceptor operationParameterExampleInterceptor =
                    new OperationParameterExampleInterceptor(openApiGeneratorConfig.getOpenApiExampleResolver());
            addInterceptor(requestBodyInterceptors, operationParameterExampleInterceptor);
            addInterceptor(schemaFieldInterceptors, operationParameterExampleInterceptor);
            addInterceptor(operationParameterInterceptors, operationParameterExampleInterceptor);
            addInterceptor(schemaInterceptors, operationParameterExampleInterceptor);
        }
    }

    public void addSchemaInterceptor(SchemaInterceptor schemaInterceptor) {
        this.schemaInterceptors.add(schemaInterceptor);
    }

    public void addSchemaFieldInterceptor(SchemaFieldInterceptor schemaFieldInterceptor) {
        this.schemaFieldInterceptors.add(schemaFieldInterceptor);
    }

    public void addOperationParameterInterceptor(OperationParameterInterceptor operationParameterInterceptor) {
        this.operationParameterInterceptors.add(operationParameterInterceptor);
    }

    public void addOperationInterceptor(OperationInterceptor operationInterceptor) {
        this.operationInterceptors.add(operationInterceptor);
    }

    public void addRequestBodyInterceptor(RequestBodyInterceptor requestBodyInterceptor) {
        this.requestBodyInterceptors.add(requestBodyInterceptor);
    }

    public void addGlobalHeader(String name, String description, boolean required) {
        globalHeaders.add(new Header(name, description, required));
    }

    private <T, U extends T> void addInterceptor(List<T> interceptors, U interceptor) {
        if (interceptors.stream().noneMatch(o -> StringUtils.equalsIgnoreCase(o.getClass().getName(), interceptor.getClass().getName()))) {
            interceptors.add(interceptor);
        }
    }

    private Paths createPathsWrapper() {
        Paths pathsWrapper = new Paths();
        pathsWrapper.putAll(createPathExtensions());
        return pathsWrapper;
    }

    private Map<String, PathItem> createPathExtensions() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllerClasses = new ArrayList<>();
        List<String> packagesWithoutRegex = removeRegexFormatFromPackages(controllerBasePackages);
        for (String controllerPackage : packagesWithoutRegex) {
            logger.debug("Scanning controller package=[{}]", controllerPackage);
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(controllerPackage)) {
                logger.debug("Scanning controller class=[{}]", beanDefinition.getBeanClassName());
                controllerClasses.add(getClass(beanDefinition));
            }
        }
        return operationsTransformer.transformOperations(controllerClasses);
    }

    private Components createComponentsWrapper() {
        Components componentsWrapper = new Components();
        componentsWrapper.setSchemas(createSchemas());
        return componentsWrapper;
    }

    private Map<String, Schema> createSchemas() {
        Map<String, Schema> schemaMap = new HashMap<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        modelPackages.forEach(modelPackage -> scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(modelPackage))));

        List<String> packagesWithoutRegex = removeRegexFormatFromPackages(modelPackages);
        Map<String, InheritanceInfo> inheritanceMap = new HashMap<>();
        for (String modelPackage : packagesWithoutRegex) {
            logger.debug("Scanning model package=[{}]", modelPackage);
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(modelPackage)) {
                logger.debug("Scanning model class=[{}]", beanDefinition.getBeanClassName());
                // populating inheritance info
                Class<?> clazz = getClass(beanDefinition);
                if (inheritanceMap.containsKey(clazz.getName()) || clazz.getAnnotation(OpenApiIgnore.class) != null) {
                    continue;
                }
                getInheritanceInfo(clazz).ifPresent(inheritanceInfo -> {
                    logger.debug("Adding entry [{}] to inheritance map", clazz.getName());
                    inheritanceMap.put(clazz.getName(), inheritanceInfo);
                });
            }
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(modelPackage)) {
                Class<?> clazz = getClass(beanDefinition);
                if (schemaMap.containsKey(clazz.getSimpleName()) || clazz.getAnnotation(OpenApiIgnore.class) != null) {
                    continue;
                }
                GenerationContext generationContext = new GenerationContext(inheritanceMap, packagesWithoutRegex);
                Schema<?> transformedComponentSchema = componentSchemaTransformer.transformSimpleSchema(clazz, generationContext);
                schemaInterceptors.forEach(schemaInterceptor -> schemaInterceptor.intercept(clazz, transformedComponentSchema));
                schemaMap.put(clazz.getSimpleName(), transformedComponentSchema);
            }

        }
        return schemaMap;
    }

    private Class<?> getClass(BeanDefinition beanDefinition) {
        try {
            return Class.forName(beanDefinition.getBeanClassName(), true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private Optional<InheritanceInfo> getInheritanceInfo(Class<?> clazz) {
        if (clazz.getAnnotation(JsonSubTypes.class) != null) {
            List<Annotation> annotations = unmodifiableList(asList(clazz.getAnnotations()));
            JsonTypeInfo jsonTypeInfo = annotations.stream()
                    .filter(annotation -> annotation instanceof JsonTypeInfo)
                    .map(annotation -> (JsonTypeInfo) annotation)
                    .findFirst()
                    .orElse(null);

            InheritanceInfo inheritanceInfo = new InheritanceInfo();
            inheritanceInfo.setDiscriminatorFieldName(getDiscriminatorName(jsonTypeInfo));
            inheritanceInfo.setDiscriminatorClassMap(scanJacksonInheritance(annotations));
            return Optional.of(inheritanceInfo);
        }
        return Optional.empty();
    }

    private String getDiscriminatorName(JsonTypeInfo jsonTypeInfo) {
        if (jsonTypeInfo == null) {
            return DEFAULT_DISCRIMINATOR_NAME;
        }
        return jsonTypeInfo.property();
    }

    private List<String> removeRegexFormatFromPackages(List<String> modelPackages) {
        return modelPackages.stream()
                .map(modelPackage -> modelPackage.replace(".*", ""))
                .collect(Collectors.toList());
    }

    private Map<String, String> scanJacksonInheritance(List<Annotation> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation instanceof JsonSubTypes)
                .map(annotation -> (JsonSubTypes) annotation)
                .flatMap(jsonSubTypesMapped -> Arrays.stream(jsonSubTypesMapped.value()))
                .collect(Collectors.toMap(o -> o.value().getSimpleName(), JsonSubTypes.Type::name));
    }

}
