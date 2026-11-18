package com.jkbootup.ecommerce.catalog.adapter.persistence;

import com.jkbootup.ecommerce.catalog.application.port.CategoryRepository;
import com.jkbootup.ecommerce.catalog.domain.Category;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcCategoryRepository implements CategoryRepository {

    private final JdbcClient jdbc;

    public JdbcCategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Category> findAll() {
        return jdbc.sql("select id, parent_id, slug, name from categories").query(JdbcCategoryRepository::row).list();
    }

    @Override
    public Optional<Category> findById(UUID id) {
        return jdbc.sql("select id, parent_id, slug, name from categories where id = :id").param("id", id)
                .query(JdbcCategoryRepository::row).optional();
    }

    @Override
    public void insert(Category c) {
        try {
            jdbc.sql("insert into categories (id, parent_id, slug, name) values (:id, :parent, :slug, :name)")
                    .param("id", c.id()).param("parent", c.parentId()).param("slug", c.slug()).param("name", c.name()).update();
        } catch (DuplicateKeyException e) {
            throw new DomainException("SLUG_TAKEN", "A category with slug " + c.slug() + " exists already");
        }
    }

    private static Category row(ResultSet rs, int i) throws SQLException {
        return new Category(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getString("slug"),
                rs.getString("name"));
    }
}
