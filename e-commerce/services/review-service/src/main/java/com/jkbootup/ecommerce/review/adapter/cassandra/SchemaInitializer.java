package com.jkbootup.ecommerce.review.adapter.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** Applies cassandra/schema.cql. Cassandra has no migration tool here, so every statement is written to be repeatable. */
public final class SchemaInitializer {

    private SchemaInitializer() {
    }

    public static void apply(CqlSession session, String replication) {
        String cql;
        try {
            cql = new ClassPathResource("cassandra/schema.cql").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing cassandra/schema.cql", e);
        }
        String withoutComments = Arrays.stream(cql.split("\n")).filter(l -> !l.strip().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        for (String statement : withoutComments.replace("${replication}", replication).split(";")) {
            if (statement.isBlank()) continue;
            session.execute(SimpleStatement.newInstance(statement.strip()).setTimeout(Duration.ofSeconds(30)));
        }
    }
}
