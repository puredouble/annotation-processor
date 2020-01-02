package me.dgpark.comment;

import com.google.auto.service.AutoService;
import com.google.common.base.CaseFormat;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@AutoService(Processor.class)
public class ColumnCommentProcessor extends AbstractProcessor {

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ColumnComment.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        for (Element element : roundEnvironment.getRootElements()) {
            if (element.getKind() == ElementKind.CLASS) {
                Entity entity = element.getAnnotation(Entity.class);
                Table table = element.getAnnotation(Table.class);

                if (entity == null && table == null) {
                    continue;
                }

                TypeElement typeElement = (TypeElement) element;
                String tableName = typeElement.getSimpleName().toString();
//                System.out.println("typeElement.getSimpleName() = " + tableName);
                if (entity != null) {
                    if (!entity.name().trim().equals("")) {
                        tableName = entity.name();
                    }
                }
                if (table != null) {
                    if (!table.name().trim().equals("")) {
                        tableName = table.name();
                    }
                }

                ClassName className = ClassName.get(typeElement);

                Set<? extends Element> fields = typeElement.getEnclosedElements()
                        .stream()
                        .filter(o -> o.getKind().isField())
                        .collect(Collectors.toSet());

                HashMap<Element, ColumnComment> map = new HashMap<>();

                for (Element field : fields) {
                    String fieldName = field.getSimpleName().toString();
                    fieldName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName);

//                    System.out.println("field = " + fieldName);
                    ColumnComment columnComment = field.getAnnotation(ColumnComment.class);

                    if (columnComment != null) {
//                        System.out.println("columnComment = " + columnComment.getClass().getSimpleName());
                        String value = columnComment.value();
//                        System.out.println("value = " + value);

                        map.put(field, columnComment);
                    }
                }

                // 메모리에 class 및 method 생성
                if (map.size() > 0) {
                    List<FieldSpec> fieldSpecList = new ArrayList<>();

                    for (Element key : map.keySet()) {
                        String fieldName = key.getSimpleName().toString();
                        fieldName = CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, fieldName);

                        // field type
                        String type = key.asType().toString();
//                        System.out.println("type = " + type);
                        String fieldType = "";

                        // reference : JPA - DB 자료형 매핑
                        // https://zetawiki.com/wiki/JPA_DB%EC%9E%90%EB%A3%8C%ED%98%95_%EB%A7%A4%ED%95%91

                        // TODO type 별 default length 설정.

                        switch (type) {
                            case "java.lang.String":
                                fieldType = "VARCHAR";
                                break;
                            case "java.lang.Long":
                                fieldType = "BIGINT";
                                break;
                            case "java.lang.Integer":
                                fieldType = "INT";
                                break;
                            case "java.lang.Double":
                                fieldType = "DOUBLE";
                                break;
                            case "java.lang.Boolean":
                                fieldType = "BIT";
                                break;
                            case "java.time.LocalDate":
                                fieldType = "DATE";
                                break;
                            case "java.time.LocalDateTime":
                                fieldType = "DATETIME";
                                break;
                            case "java.time.LocalTime":
                                fieldType = "TIME";
                                break;
                            default:
                                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "FATAL ERROR: ColumnComment annotation not supported type : " + type);
                        }

                        System.out.println("fieldType = " + fieldType);

                        Column column = key.getAnnotation(Column.class);

                        if (column != null) {
                            // nullable
                            boolean nullable = column.nullable();
                            System.out.println("nullable = " + nullable);
                            // field length
                            int length = column.length();
                            System.out.println("length = " + length);

                            ColumnComment columnComment = map.get(key);

                            String alterField = "\"ALTER TABLE \u0060" + tableName + "\u0060 CHANGE \u0060" + fieldName + "\u0060 \u0060" + fieldName + "\u0060 ";

                            if (nullable) {
                                alterField += "NOT NULL ";
                            }

                            alterField += fieldType + " ";

                            if (length > 0) {
                                alterField += "(" + length + ") ";
                            }

                            alterField += "COMMENT '" + columnComment.value() + "'\"";

                            FieldSpec fieldSpec = FieldSpec
                                    .builder(String.class, columnComment.value(), Modifier.PUBLIC)
//                                    .initializer(
//                                            "\"ALTER TABLE \u0060" + tableName + "\u0060 CHANGE \u0060" + fieldName + "\u0060 \u0060" + fieldName + "\u0060 INT( 11 ) COMMENT '" + columnComment.value() + "'\""
//                                    )
                                    .initializer(alterField)
                                    .build();

                            fieldSpecList.add(fieldSpec);
                        }

                    }

                    TypeSpec typeSpec = TypeSpec.classBuilder(typeElement.getSimpleName() + "Comment")
                            .addModifiers(Modifier.PUBLIC)
                            .addFields(fieldSpecList)
                            .build();

                    // 실제 파일을 생성
                    Filer filer = processingEnv.getFiler();

                    try {
                        JavaFile.builder(className.packageName(), typeSpec)
                                .build()
                                .writeTo(filer);
                    } catch (IOException e) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "FATAL ERROR: " + e);
                    }
                }
            }
        }

        return true;
    }
}
