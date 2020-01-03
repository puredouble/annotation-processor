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
import javax.persistence.*;
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
                String tableName = getTableName(entity, table, typeElement);

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
                        FieldType fieldType = null;

                        // reference : JPA - DB 자료형 매핑
                        // https://zetawiki.com/wiki/JPA_DB%EC%9E%90%EB%A3%8C%ED%98%95_%EB%A7%A4%ED%95%91

                        // type 별 default length 설정.
                        if (type.equals("java.lang.String")) {
                            Lob lob = key.getAnnotation(Lob.class);
                            if (lob != null) {
                                fieldType = FieldType.LONGTEXT;
                            }
                            else {
                                fieldType = getFieldType(type, key);
                            }
                        }
                        else {
                            fieldType = getFieldType(type, key);
                        }

                        System.out.println("fieldType = " + fieldType.name());

//                        int length = getDefaultLength(fieldType);

                        Column column = key.getAnnotation(Column.class);

                        // nullable
                        boolean nullable = true;
                        // field length
                        int length = 0;

                        if (column != null) {
                            // nullable
                            nullable = column.nullable();
                            System.out.println("nullable = " + nullable);
                            // field length
                            length = column.length();
                            System.out.println("length = " + length);
                        }

                        length = getColumnLength(fieldType, length);

                        ColumnComment columnComment = map.get(key);

                        String alterField = "\"ALTER TABLE \u0060" + tableName + "\u0060 CHANGE \u0060" + fieldName + "\u0060 \u0060" + fieldName + "\u0060 ";

                        if (!nullable) {
                            alterField += "NOT NULL ";
                        }

                        alterField += fieldType.name() + " ";

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

    private int getColumnLength(FieldType fieldType, int length) {
        // String 이 아닌 field 중 length 가 255 면 default 값이기 때문에 default length 입력.
        if (!fieldType.equals(FieldType.VARCHAR) && length == 255) {
            length = getDefaultLength(fieldType);
        }

        // String 값인데 length 가 없으면 255를 기본으로 넣어줌.
        if (fieldType.equals(FieldType.VARCHAR) && length == 0) {
            length = 255;
        }

        return length;
    }

    private Integer getDefaultLength(FieldType fieldType) {
        Integer length = null;
        switch (fieldType) {
            case FLOAT:
            case DOUBLE:
            case DECIMAL:
            case DATE:
            case TIME:
                break;
            case DATETIME:
                length = 6;
                break;
            case TINYINT:
                length = 4;
                break;
            case SMALLINT:
                length = 6;
                break;
            case VARCHAR:
                length = 255;
                break;
            case BIGINT:
                length = 20;
                break;
            case INT:
                length = 11;
                break;
            case BIT:
                length = 1;
                break;
            default:
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "FATAL ERROR: ColumnComment annotation not supported type : " + fieldType);
        }
        return length;
    }

    private FieldType getFieldType(String type, Element field) {
        FieldType fieldType = null;
        switch (type) {
            case "java.lang.Float":
                fieldType = FieldType.FLOAT;
                break;
            case "java.lang.Double":
                fieldType = FieldType.DOUBLE;
                break;
            case "java.lang.BigDecimal":
                fieldType = FieldType.DECIMAL;
                break;
            case "java.lang.Byte":
                fieldType = FieldType.TINYINT;
                break;
            case "java.lang.Short":
                fieldType = FieldType.SMALLINT;
                break;
            case "java.lang.String":
                fieldType = FieldType.VARCHAR;
                break;
            case "java.lang.Long":
                fieldType = FieldType.BIGINT;
                break;
            case "java.lang.Integer":
                fieldType = FieldType.INT;
                break;
            case "java.lang.Boolean":
                fieldType = FieldType.BIT;
                break;
            case "java.time.LocalDate":
                fieldType = FieldType.DATE;
                break;
            case "java.time.LocalDateTime":
                fieldType = FieldType.DATETIME;
                break;
            case "java.time.LocalTime":
                fieldType = FieldType.TIME;
                break;
            default:
                Enumerated enumerated = field.getAnnotation(Enumerated.class);
                System.out.println("enumerated = " + enumerated);
                if (enumerated != null) {
                    fieldType = FieldType.VARCHAR;
                    break;
                }

                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "FATAL ERROR: ColumnComment annotation not supported type : " + type);
        }
        return fieldType;
    }

    private String getTableName(Entity entity, Table table, TypeElement typeElement) {
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
        return tableName;
    }

}
