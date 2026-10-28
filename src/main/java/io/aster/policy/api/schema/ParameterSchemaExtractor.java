package io.aster.policy.api.schema;

import aster.core.inference.TypeInference;
import aster.core.ir.CoreModel;
import io.aster.policy.parser.InProcessCnlParser;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CNL 参数模式提取器
 *
 * 从 CNL 源代码中提取函数参数的结构化模式信息，用于：
 * 1. 动态生成表单（根据参数名和类型生成输入控件）
 * 2. API 客户端提示（参数补全和验证）
 * 3. 文档生成（自动生成 API 文档）
 */
public class ParameterSchemaExtractor {

    private static final Logger LOG = Logger.getLogger(ParameterSchemaExtractor.class);

    /**
     * 参数类型枚举
     */
    public enum TypeKind {
        PRIMITIVE,    // 基本类型（Int、String、Bool 等）
        STRUCT,       // 结构体类型（Data 定义的复合类型）
        ENUM,         // 枚举类型
        LIST,         // 列表类型
        MAP,          // 映射类型
        OPTION,       // 可选类型
        RESULT,       // 结果类型
        FUNCTION,     // 函数类型
        UNKNOWN       // 未知类型
    }

    /**
     * 字段信息（用于结构体类型）
     */
    public record FieldInfo(
        String name,
        String type,
        TypeKind typeKind,
        String description
    ) {}

    /**
     * 参数信息
     */
    public record ParameterInfo(
        String name,           // 参数名
        String type,           // 类型名（显示用）
        TypeKind typeKind,     // 类型分类
        boolean optional,      // 是否可选
        int position,          // 参数位置（0 开始）
        List<FieldInfo> fields // 结构体字段（仅 STRUCT 类型）
    ) {}

    /**
     * 模式提取结果
     */
    public record SchemaResult(
        boolean success,
        String moduleName,
        String functionName,
        List<ParameterInfo> parameters,
        String error
    ) {
        public static SchemaResult success(String moduleName, String functionName, List<ParameterInfo> params) {
            return new SchemaResult(true, moduleName, functionName, params, null);
        }

        public static SchemaResult error(String errorMessage) {
            return new SchemaResult(false, null, null, List.of(), errorMessage);
        }
    }

    /**
     * 从 CNL 源代码提取参数模式
     *
     * @param source       CNL 源代码
     * @param functionName 目标函数名（可选，为空时使用第一个函数）
     * @param locale       语言代码（如 "zh-CN"、"de-DE"）
     * @return 模式提取结果
     */
    public static SchemaResult extractSchema(String source, String functionName, String locale) {
        try {
            // 1. 解析 CNL → AST
            InProcessCnlParser.ParseResult parseResult = InProcessCnlParser.parse(source, locale);

            // 2. 确定目标函数
            String targetFunction = functionName;
            if (targetFunction == null || targetFunction.isBlank() || "evaluate".equals(targetFunction)) {
                targetFunction = parseResult.firstFunctionName();
            }
            if (targetFunction == null) {
                return SchemaResult.error("CNL 中未找到函数定义");
            }

            // 3. 名称解析后的 Core IR（parser 内已完成 R1 → lower → R2）
            CoreModel.Module coreModule = parseResult.core();

            // 4. 构建类型映射（用于解析结构体字段）
            Map<String, CoreModel.Data> dataTypes = buildDataTypeMap(coreModule);

            // 5. 查找目标函数并提取参数
            for (CoreModel.Decl decl : coreModule.decls) {
                if (decl instanceof CoreModel.Func func && func.name.equals(targetFunction)) {
                    List<ParameterInfo> params = extractFunctionParams(func, dataTypes);
                    return SchemaResult.success(coreModule.name, func.name, params);
                }
            }

            return SchemaResult.error("未找到函数: " + targetFunction);

        } catch (InProcessCnlParser.CnlParseException e) {
            LOG.warnf("CNL 解析失败: %s", e.getMessage());
            return SchemaResult.error("CNL 解析失败: " + e.getMessage());
        } catch (Exception e) {
            LOG.errorf(e, "模式提取失败: %s", e.getMessage());
            return SchemaResult.error("模式提取失败: " + e.getMessage());
        }
    }

    /**
     * 构建数据类型映射（类型名 → Data 定义）
     */
    private static Map<String, CoreModel.Data> buildDataTypeMap(CoreModel.Module module) {
        Map<String, CoreModel.Data> map = new HashMap<>();
        if (module.decls != null) {
            for (CoreModel.Decl decl : module.decls) {
                if (decl instanceof CoreModel.Data data) {
                    map.put(data.name, data);
                }
            }
        }
        return map;
    }

    /**
     * 提取函数参数信息
     */
    private static List<ParameterInfo> extractFunctionParams(
        CoreModel.Func func,
        Map<String, CoreModel.Data> dataTypes
    ) {
        if (func.params == null || func.params.isEmpty()) {
            return List.of();
        }

        List<ParameterInfo> result = new ArrayList<>();
        for (int i = 0; i < func.params.size(); i++) {
            CoreModel.Param param = func.params.get(i);
            ParameterInfo info = buildParameterInfo(param, i, dataTypes);
            result.add(info);
        }
        return result;
    }

    /**
     * 构建单个参数的信息
     */
    private static ParameterInfo buildParameterInfo(
        CoreModel.Param param,
        int position,
        Map<String, CoreModel.Data> dataTypes
    ) {
        String typeName = getTypeName(param.type);
        TypeKind typeKind = getTypeKind(param.type, dataTypes);
        boolean optional = isOptionalType(param.type);
        List<FieldInfo> fields = Collections.emptyList();

        // 如果是结构体类型，提取字段信息
        if (typeKind == TypeKind.STRUCT) {
            String baseTypeName = getBaseTypeName(param.type);
            CoreModel.Data dataType = dataTypes.get(baseTypeName);
            if (dataType != null) {
                fields = extractFields(dataType, dataTypes);
            }
        }

        return new ParameterInfo(
            param.name,
            typeName,
            typeKind,
            optional,
            position,
            fields
        );
    }

    /**
     * 提取结构体字段信息
     */
    private static List<FieldInfo> extractFields(
        CoreModel.Data dataType,
        Map<String, CoreModel.Data> dataTypes
    ) {
        if (dataType.fields == null) {
            return List.of();
        }

        List<FieldInfo> result = new ArrayList<>();
        for (CoreModel.Field field : dataType.fields) {
            String typeName = getTypeName(field.type);
            TypeKind typeKind = getTypeKind(field.type, dataTypes);
            result.add(new FieldInfo(field.name, typeName, typeKind, null));
        }
        return result;
    }

    /**
     * 获取类型的显示名称
     */
    private static String getTypeName(CoreModel.Type type) {
        if (type == null) {
            return "Unknown";
        }

        if (type instanceof CoreModel.TypeName tn) {
            return tn.name;
        }
        if (type instanceof CoreModel.TypeVar tv) {
            return tv.name;
        }
        if (type instanceof CoreModel.ListT lt) {
            return "List<" + getTypeName(lt.type) + ">";
        }
        if (type instanceof CoreModel.MapT mt) {
            return "Map<" + getTypeName(mt.key) + ", " + getTypeName(mt.val) + ">";
        }
        if (type instanceof CoreModel.Option opt) {
            return getTypeName(opt.type) + "?";
        }
        if (type instanceof CoreModel.Maybe maybe) {
            return getTypeName(maybe.type) + "?";
        }
        if (type instanceof CoreModel.Result res) {
            return "Result<" + getTypeName(res.ok) + ", " + getTypeName(res.err) + ">";
        }
        if (type instanceof CoreModel.TypeApp app) {
            StringBuilder sb = new StringBuilder(app.base);
            if (app.args != null && !app.args.isEmpty()) {
                sb.append("<");
                for (int i = 0; i < app.args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(getTypeName(app.args.get(i)));
                }
                sb.append(">");
            }
            return sb.toString();
        }
        if (type instanceof CoreModel.FuncType ft) {
            StringBuilder sb = new StringBuilder("(");
            if (ft.params != null) {
                for (int i = 0; i < ft.params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(getTypeName(ft.params.get(i)));
                }
            }
            sb.append(") -> ");
            sb.append(getTypeName(ft.ret));
            return sb.toString();
        }
        if (type instanceof CoreModel.PiiType pii) {
            return getTypeName(pii.baseType) + " @pii";
        }

        return type.getClass().getSimpleName();
    }

    /**
     * 获取基础类型名（去除包装器）
     */
    private static String getBaseTypeName(CoreModel.Type type) {
        if (type instanceof CoreModel.TypeName tn) {
            return tn.name;
        }
        if (type instanceof CoreModel.Option opt) {
            return getBaseTypeName(opt.type);
        }
        if (type instanceof CoreModel.Maybe maybe) {
            return getBaseTypeName(maybe.type);
        }
        if (type instanceof CoreModel.PiiType pii) {
            return getBaseTypeName(pii.baseType);
        }
        return getTypeName(type);
    }

    /**
     * 判断类型分类
     */
    private static TypeKind getTypeKind(CoreModel.Type type, Map<String, CoreModel.Data> dataTypes) {
        if (type == null) {
            return TypeKind.UNKNOWN;
        }

        if (type instanceof CoreModel.TypeName tn) {
            // 检查是否为基本类型
            if (isPrimitiveType(tn.name)) {
                return TypeKind.PRIMITIVE;
            }
            // 检查是否为已定义的结构体
            if (dataTypes.containsKey(tn.name)) {
                return TypeKind.STRUCT;
            }
            // 可能是枚举或外部类型
            return TypeKind.UNKNOWN;
        }
        if (type instanceof CoreModel.TypeVar) {
            return TypeKind.UNKNOWN;
        }
        if (type instanceof CoreModel.ListT) {
            return TypeKind.LIST;
        }
        if (type instanceof CoreModel.MapT) {
            return TypeKind.MAP;
        }
        if (type instanceof CoreModel.Option || type instanceof CoreModel.Maybe) {
            return TypeKind.OPTION;
        }
        if (type instanceof CoreModel.Result) {
            return TypeKind.RESULT;
        }
        if (type instanceof CoreModel.FuncType) {
            return TypeKind.FUNCTION;
        }
        if (type instanceof CoreModel.PiiType pii) {
            return getTypeKind(pii.baseType, dataTypes);
        }
        if (type instanceof CoreModel.TypeApp app) {
            // TypeApp 可能是泛型结构体
            if (dataTypes.containsKey(app.base)) {
                return TypeKind.STRUCT;
            }
            return TypeKind.UNKNOWN;
        }

        return TypeKind.UNKNOWN;
    }

    /**
     * 判断是否为可选类型
     */
    private static boolean isOptionalType(CoreModel.Type type) {
        return type instanceof CoreModel.Option || type instanceof CoreModel.Maybe;
    }

    /**
     * 判断是否为基本类型
     *
     * 委托 TypeInference.isPrimitiveType() 处理规范名（Int, Float, Bool 等），
     * 补充英文别名（integer, decimal, string 等）。
     * 中文/德语类型名由 Canonicalizer 在解析阶段翻译为英文规范名，
     * Core IR 中不会出现非英文类型名，因此无需匹配。
     */
    static boolean isPrimitiveType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        if (TypeInference.isPrimitiveType(typeName)) {
            return true;
        }
        return switch (typeName.toLowerCase()) {
            case "int", "float", "bool", "text", "long", "double",
                 "integer", "decimal", "string", "boolean",
                 "date", "datetime", "time", "number" -> true;
            default -> false;
        };
    }
}
