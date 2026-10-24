package io.aster.db;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从静态 SQL 中提取会删除表或列的对象。 */
final class DropDetector {
    private static final Pattern DROP_TABLE = Pattern.compile(
        "\\bDROP\\s+TABLE(?:\\s+IF\\s+EXISTS)?\\s+([\\w\".]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_COLUMN = Pattern.compile(
        "\\bALTER\\s+TABLE\\s+([\\w\".]+)\\s+DROP\\s+COLUMN(?:\\s+IF\\s+EXISTS)?\\s+([\\w\"]+)",
        Pattern.CASE_INSENSITIVE);

    private DropDetector() {}

    static String stripCommentsAndStrings(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            if (sql.startsWith("--", i)) {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
            } else if (sql.startsWith("/*", i)) {
                i = skipBlock(sql, i + 2);
            } else if (sql.charAt(i) == '\'') {
                i = skipString(sql, i + 1);
                out.append(' ');
            } else {
                out.append(sql.charAt(i++));
            }
        }
        return out.toString();
    }

    private static int skipBlock(String sql, int i) {
        int depth = 1;
        while (i < sql.length() && depth > 0) {
            if (sql.startsWith("/*", i)) { depth++; i += 2; }
            else if (sql.startsWith("*/", i)) { depth--; i += 2; }
            else i++;
        }
        return i;
    }

    private static int skipString(String sql, int i) {
        while (i < sql.length()) {
            if (sql.charAt(i++) == '\'') {
                if (i < sql.length() && sql.charAt(i) == '\'') i++;
                else break;
            }
        }
        return i;
    }

    static Set<String> detect(String stripped) {
        Set<String> found = new LinkedHashSet<>();
        collect(DROP_TABLE, stripped, found, false);
        collect(DROP_COLUMN, stripped, found, true);
        return found;
    }

    private static void collect(Pattern pattern, String sql, Set<String> found, boolean column) {
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String object = normalize(matcher.group(1));
            if (column) object += "." + normalize(matcher.group(2));
            found.add(object);
        }
    }

    private static String normalize(String name) {
        String normalized = name.replace("\"", "");
        if (normalized.startsWith("public.")) normalized = normalized.substring("public.".length());
        return normalized;
    }
}
