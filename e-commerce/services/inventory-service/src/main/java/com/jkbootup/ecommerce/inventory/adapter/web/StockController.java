package com.jkbootup.ecommerce.inventory.adapter.web;

import com.jkbootup.ecommerce.inventory.application.ReservationService;
import com.jkbootup.ecommerce.inventory.domain.DomainException;
import com.jkbootup.ecommerce.inventory.domain.Sku;
import com.jkbootup.ecommerce.inventory.domain.StockLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal stock administration (stock-takes, imports). */
@RestController
@RequestMapping("/internal/stock")
public class StockController {

    public record SetStockRequest(@Min(0) @Max(100_000_000) int available) {}

    public record StockResponse(String sku, int available, int reserved, boolean inStock) {
        static StockResponse of(StockLevel l) {
            return new StockResponse(l.sku().value(), l.available(), l.reserved(), l.inStock());
        }
    }

    private final ReservationService service;

    public StockController(ReservationService service) {
        this.service = service;
    }

    @GetMapping("/{sku}")
    public StockResponse get(@PathVariable String sku) {
        return service.level(Sku.of(sku)).map(StockResponse::of)
                .orElseThrow(() -> new DomainException("NOT_FOUND", "Unknown SKU " + sku));
    }

    @PutMapping("/{sku}")
    public StockResponse set(@PathVariable String sku, @Valid @RequestBody SetStockRequest request) {
        service.setAvailable(Sku.of(sku), request.available());
        return get(sku);
    }
}
