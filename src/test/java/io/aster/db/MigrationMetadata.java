package io.aster.db;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析不可逆迁移首行声明的契约元数据。 */
record MigrationMetadata(String contractOf, List<String> objects) {
    private static final Pattern HEADER = Pattern.compile("^-- aster-migration: (\\{.*\\})$");
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static Optional<MigrationMetadata> parse(String firstLine) {
        Matcher matcher = HEADER.matcher(firstLine);
        if (!matcher.matches()) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(matcher.group(1), MigrationMetadata.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
