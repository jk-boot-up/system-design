package com.jkbootup.ecommerce.catalog.adapter.persistence;

import com.jkbootup.ecommerce.catalog.application.port.ProductRepository;
import com.jkbootup.ecommerce.catalog.domain.DomainException;
import com.jkbootup.ecommerce.catalog.domain.Money;
import com.jkbootup.ecommerce.catalog.domain.Product;
import com.jkbootup.ecommerce.catalog.domain.ProductDetails;
import com.jkbootup.ecommerce.catalog.domain.ProductImage;
import com.jkbootup.ecommerce.catalog.domain.ProductStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Products in PostgreSQL; attributes are a JSONB column, images a child table. */
public class JdbcProductRepository implements ProductRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};
    private static final String COLUMNS = """
            id, sku, name, description, category_id, brand, price_amount, currency, attributes::text as attributes,
            status, version, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcProductRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from products where id = :id").param("id", id)
                .query(this::row).optional().map(p -> withImages(List.of(p)).getFirst());
    }

    @Override
    public List<Product> findBySkus(Collection<String> skus) {
        return withImages(jdbc.sql("select " + COLUMNS + " from products where sku in (:skus)").param("skus", skus)
                .query(this::row).list());
    }

    @Override
    public void insert(Product p) {
        try {
            jdbc.sql("""
                            insert into products (id, sku, name, description, category_id, brand, price_amount, currency, attributes,
                                                  status, version, created_at, updated_at)
                            values (:id, :sku, :name, :description, :category, :brand, :amount, :currency, cast(:attributes as jsonb),
                                    :status, :version, :createdAt, :updatedAt)""")
                    .params(params(p)).update();
        } catch (DuplicateKeyException e) {
            throw new DomainException("SKU_TAKEN", "A product with SKU " + p.sku() + " exists already");
        }
        insertImages(p);
    }

    @Override
    public boolean update(Product p, long expectedVersion) {
        Map<String, Object> params = params(p);
        params.put("expected", expectedVersion);
        int n = jdbc.sql("""
                        update products set name = :name, description = :description, category_id = :category, brand = :brand,
                               price_amount = :amount, currency = :currency, attributes = cast(:attributes as jsonb),
                               status = :status, version = :version, updated_at = :updatedAt
                         where id = :id and version = :expected""")
                .params(params).update();
        if (n == 0) return false;
        jdbc.sql("delete from product_images where product_id = :id").param("id", p.id()).update();
        insertImages(p);
        return true;
    }

    private void insertImages(Product p) {
        for (ProductImage i : p.images())
            jdbc.sql("insert into product_images (product_id, position, object_key, content_type) values (:id, :pos, :key, :type)")
                    .param("id", p.id()).param("pos", i.position()).param("key", i.objectKey()).param("type", i.contentType())
                    .update();
    }

    private Map<String, Object> params(Product p) {
        ProductDetails d = p.details();
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", p.id());
        m.put("sku", p.sku());
        m.put("name", d.name());
        m.put("description", d.description());
        m.put("category", d.categoryId());
        m.put("brand", d.brand());
        m.put("amount", d.price().amount());
        m.put("currency", d.price().currency().getCurrencyCode());
        m.put("attributes", json.writeValueAsString(d.attributes()));
        m.put("status", d.status().name());
        m.put("version", p.version());
        m.put("createdAt", Timestamp.from(p.createdAt()));
        m.put("updatedAt", Timestamp.from(p.updatedAt()));
        return m;
    }

    private Product row(ResultSet rs, int i) throws SQLException {
        var details = new ProductDetails(rs.getString("name"), rs.getString("description"), rs.getObject("category_id", UUID.class),
                rs.getString("brand"), new Money(rs.getBigDecimal("price_amount"), Currency.getInstance(rs.getString("currency"))),
                json.readValue(rs.getString("attributes"), ATTRIBUTES), ProductStatus.valueOf(rs.getString("status")));
        return new Product(rs.getObject("id", UUID.class), rs.getString("sku"), details, List.of(), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private List<Product> withImages(List<Product> found) {
        if (found.isEmpty()) return found;
        record Row(UUID productId, ProductImage image) {}
        Map<UUID, List<ProductImage>> images = jdbc.sql("""
                        select product_id, position, object_key, content_type from product_images
                         where product_id in (:ids) order by product_id, position""")
                .param("ids", found.stream().map(Product::id).toList())
                .query((rs, n) -> new Row(rs.getObject("product_id", UUID.class),
                        new ProductImage(rs.getInt("position"), rs.getString("object_key"), rs.getString("content_type"))))
                .list().stream().collect(Collectors.groupingBy(Row::productId,
                        Collectors.mapping(Row::image, Collectors.toCollection(ArrayList::new))));
        return found.stream().map(p -> new Product(p.id(), p.sku(), p.details(), images.getOrDefault(p.id(), new ArrayList<>()),
                p.version(), p.createdAt(), p.updatedAt())).toList();
    }
}
