package io.aster.db;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DropDetectorTest {
    @Test
    void ignoresCommentsAndQuotedText() {
        String sql = "-- DROP TABLE commented\nSELECT 'DROP TABLE literal'; /* DROP TABLE block */";
        assertThat(DropDetector.detect(DropDetector.stripCommentsAndStrings(sql))).isEmpty();
    }

    @Test
    void detectsConditionalAndSchemaQualifiedDrops() {
        String sql = "DROP TABLE IF EXISTS public.\"old_items\"; " +
            "ALTER TABLE public.items DROP COLUMN IF EXISTS \"retired\"";
        assertThat(DropDetector.detect(DropDetector.stripCommentsAndStrings(sql)))
            .containsExactlyInAnyOrder("old_items", "items.retired");
    }

    @Test
    void detectsMultipleDropsAndKeepsDollarQuotedExecuteForSeparateRule() {
        String sql = "DROP TABLE alpha; DROP TABLE beta; DO $$ BEGIN EXECUTE 'DROP TABLE hidden'; END $$;";
        String stripped = DropDetector.stripCommentsAndStrings(sql);
        assertThat(DropDetector.detect(stripped)).containsExactlyInAnyOrder("alpha", "beta");
        assertThat(stripped).contains("EXECUTE");
    }
}
