package com.bookstore.migration.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HashesTest {

    @Test
    void sameContentProducesSameHashRegardlessOfInsertionOrder() {
        String h1 = Hashes.sha256(Map.of("a", 1, "b", "2"));
        String h2 = Hashes.sha256(new java.util.LinkedHashMap<>(Map.of("b", "2", "a", 1)));
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
    }

    @Test
    void differentContentProducesDifferentHash() {
        assertThat(Hashes.sha256(Map.of("a", 1)))
                .isNotEqualTo(Hashes.sha256(Map.of("a", 2)));
    }
}
