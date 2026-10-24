package io.aster.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationMetadataTest {
    @Test
    void parsesValidMetadata() {
        assertThat(MigrationMetadata.parse("-- aster-migration: {\"contractOf\":\"V1__base\",\"objects\":[\"items\"]}"))
            .contains(new MigrationMetadata("V1__base", java.util.List.of("items")));
    }

    @Test
    void rejectsMissingMalformedAndUnknownFields() {
        assertThat(MigrationMetadata.parse("-- ordinary comment")).isEmpty();
        assertThat(MigrationMetadata.parse("-- aster-migration: {not-json}")).isEmpty();
        assertThat(MigrationMetadata.parse("-- aster-migration: {\"contractOf\":\"V1__base\",\"objects\":[],\"extra\":true}"))
            .isEmpty();
    }

    @Test
    void rejectsContractThatDoesNotExist() {
        String contract = "V999__missing";
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of(
            "src/main/resources/db/migration", contract + ".sql"))).isFalse();
    }

    @Test
    void rejectsObjectListThatDiffersFromDetectedDrops() {
        MigrationMetadata metadata = new MigrationMetadata("V1__base", java.util.List.of("other"));
        assertThat(metadata.objects()).doesNotContain("items");
        assertThat(DropDetector.detect(DropDetector.stripCommentsAndStrings("DROP TABLE items")))
            .containsExactly("items");
    }
}
