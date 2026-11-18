package com.jkbootup.ecommerce.catalog.adapter.web;

import com.jkbootup.ecommerce.catalog.application.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service API, reachable only inside the cluster (network policy); the gateway never routes /internal. */
@RestController
public class InternalController {

    public record PricesRequest(@NotNull @Size(max = CatalogService.MAX_PRICE_LOOKUP) List<String> skus) {}

    public record PriceItem(String sku, String name, ProductView.MoneyView price, String status) {}

    public record PricesResponse(List<PriceItem> items) {}

    private final CatalogService service;

    public InternalController(CatalogService service) {
        this.service = service;
    }

    @PostMapping("/internal/products/prices")
    public PricesResponse prices(@Valid @RequestBody PricesRequest request) {
        return new PricesResponse(service.pricesFor(request.skus()).stream().map(p -> new PriceItem(p.sku(), p.details().name(),
                new ProductView.MoneyView(p.details().price().amount().toPlainString(), p.details().price().currency().getCurrencyCode()),
                p.details().status().name())).toList());
    }
}
