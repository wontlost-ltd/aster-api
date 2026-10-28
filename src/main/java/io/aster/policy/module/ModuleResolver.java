package io.aster.policy.module;

import aster.core.ast.Decl;
import aster.core.ir.CoreModel;
import aster.core.module.ModuleGraph;
import aster.core.module.ModuleKey;
import io.aster.policy.entity.PolicyVersion;
import io.aster.policy.parser.InProcessCnlParser;
import io.aster.replay.core.module.ModuleGraphResolver;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves pinned CNL imports into a Core module graph for ADR 0015 linking.
 */
@ApplicationScoped
public class ModuleResolver implements ModuleGraphResolver {

    private static final Logger LOG = Logger.getLogger(ModuleResolver.class);
    private static final int ROOT_VERSION = 0;

    @Override
    public ModuleGraph resolveGraph(
            String tenantId,
            CoreModel.Module rootCore,
            List<Decl.Import> rootImports,
            String locale) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_NOT_VISIBLE,
                "Module imports require a tenant context");
        }
        Objects.requireNonNull(rootCore, "rootCore");

        String rootName = rootCore.name == null || rootCore.name.isBlank() ? "__root__" : rootCore.name;
        ModuleKey rootKey = new ModuleKey(rootName, ROOT_VERSION);
        Map<ModuleKey, CoreModel.Module> modules = new LinkedHashMap<>();
        List<ModuleGraph.ImportEdge> edges = new ArrayList<>();
        Map<CacheKey, ParsedModule> cache = new HashMap<>();
        Set<ModuleKey> resolved = new HashSet<>();
        ArrayDeque<ModuleKey> stack = new ArrayDeque<>();

        modules.put(rootKey, rootCore);
        stack.push(rootKey);
        resolveImports(
            tenantId,
            rootKey,
            rootImports == null ? List.of() : rootImports,
            locale,
            modules,
            edges,
            cache,
            resolved,
            stack
        );
        stack.pop();
        resolved.add(rootKey);

        return new ModuleGraph(rootKey, modules, edges);
    }

    private void resolveImports(
            String tenantId,
            ModuleKey fromKey,
            List<Decl.Import> imports,
            String locale,
            Map<ModuleKey, CoreModel.Module> modules,
            List<ModuleGraph.ImportEdge> edges,
            Map<CacheKey, ParsedModule> cache,
            Set<ModuleKey> resolved,
            ArrayDeque<ModuleKey> stack) {
        for (Decl.Import imp : imports) {
            ModuleKey toKey = parsePinnedRef(imp);
            edges.add(new ModuleGraph.ImportEdge(fromKey, importAlias(imp, toKey), toKey));

            if (stack.contains(toKey)) {
                throw cycle(stack, toKey);
            }
            if (resolved.contains(toKey)) {
                continue;
            }

            PolicyVersion version = PolicyVersion.findLibraryVersion(
                tenantId, toKey.moduleName(), (long) toKey.version());
            if (version == null) {
                throw notFoundOrNotVisible(tenantId, toKey);
            }

            ParsedModule parsed = parseImportedModule(tenantId, toKey, version, locale, cache);
            modules.putIfAbsent(toKey, parsed.coreModule());

            stack.push(toKey);
            resolveImports(
                tenantId,
                toKey,
                parsed.imports(),
                locale,
                modules,
                edges,
                cache,
                resolved,
                stack
            );
            stack.pop();
            resolved.add(toKey);
        }
    }

    private ModuleKey parsePinnedRef(Decl.Import imp) {
        if (imp.version() == null) {
            throw new ModuleResolutionException(
                ModuleResolutionException.Code.IMPORT_VERSION_REQUIRED,
                "Import version is required");
        }
        if (imp.path() == null || imp.path().isBlank()) {
            throw new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_VERSION_NOT_FOUND,
                "Imported module path is empty");
        }
        return new ModuleKey(imp.path().trim(), imp.version());
    }

    private ModuleResolutionException notFoundOrNotVisible(String tenantId, ModuleKey key) {
        if (PolicyVersion.existsLibraryVersionInTenant(tenantId, key.moduleName(), (long) key.version())) {
            return new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_NOT_VISIBLE,
                "Imported module is not visible");
        }

        List<String> candidates = PolicyVersion.findLibraryVersions(tenantId, key.moduleName())
            .stream()
            .map(String::valueOf)
            .toList();
        return new ModuleResolutionException(
            ModuleResolutionException.Code.MODULE_VERSION_NOT_FOUND,
            "Imported module version was not found",
            candidates
        );
    }

    private ParsedModule parseImportedModule(
            String tenantId,
            ModuleKey key,
            PolicyVersion version,
            String locale,
            Map<CacheKey, ParsedModule> cache) {
        String content = version.content == null ? "" : version.content;

        // fail-closed（ADR 0022 §11.5，Codex 持久化复核）：带用户别名的版本被跨模块引用时，
        // 本路径尚未支持注入 aliasSet → 若无别名解析，会用无别名重解释带别名的库模块（静默错解析）。
        // 在 aliasSet-aware 跨模块解析落地前，拒绝引用带别名的 library 版本，而非静默降级。
        if (version.aliasSet != null && !version.aliasSet.isBlank()) {
            throw new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_NOT_VISIBLE,
                "Imported module uses user-defined keyword aliases; cross-module resolution with "
                    + "aliases is not yet supported (refusing to resolve without them, fail-closed)"
            );
        }

        CacheKey cacheKey = new CacheKey(tenantId, key.moduleName(), key.version(), sha256(content));
        ParsedModule cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            InProcessCnlParser.ParseResult parseResult = InProcessCnlParser.parse(content, locale, null);
            CoreModel.Module coreModule = parseResult.core();
            ParsedModule parsed = new ParsedModule(coreModule, importsOf(parseResult.module()));
            cache.put(cacheKey, parsed);
            LOG.debugf("Resolved module import %s@%d for tenant %s", key.moduleName(), key.version(), tenantId);
            return parsed;
        } catch (InProcessCnlParser.CnlParseException e) {
            throw new ModuleResolutionException(
                ModuleResolutionException.Code.MODULE_NOT_VISIBLE,
                "Imported module could not be parsed",
                e
            );
        }
    }

    private List<Decl.Import> importsOf(aster.core.ast.Module module) {
        if (module == null || module.decls() == null) {
            return List.of();
        }
        return module.decls().stream()
            .filter(Decl.Import.class::isInstance)
            .map(Decl.Import.class::cast)
            .toList();
    }

    private String importAlias(Decl.Import imp, ModuleKey key) {
        if (imp.alias() != null && !imp.alias().isBlank()) {
            return imp.alias().trim();
        }
        String moduleName = key.moduleName();
        int dot = moduleName.lastIndexOf('.');
        return dot >= 0 && dot < moduleName.length() - 1
            ? moduleName.substring(dot + 1)
            : moduleName;
    }

    private ModuleResolutionException cycle(ArrayDeque<ModuleKey> stack, ModuleKey repeated) {
        List<String> cycle = new ArrayList<>();
        for (ModuleKey key : stack) {
            cycle.add(key.moduleName() + "@" + key.version());
            if (key.equals(repeated)) {
                break;
            }
        }
        java.util.Collections.reverse(cycle);
        cycle.add(repeated.moduleName() + "@" + repeated.version());
        return new ModuleResolutionException(
            ModuleResolutionException.Code.MODULE_CYCLE,
            "Module import cycle detected: " + String.join(" -> ", cycle)
        );
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash imported module source", e);
        }
    }

    private record CacheKey(String tenantId, String moduleName, int version, String sourceHash) {}

    private record ParsedModule(CoreModel.Module coreModule, List<Decl.Import> imports) {}
}
