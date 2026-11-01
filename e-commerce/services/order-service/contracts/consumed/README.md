# Consumed contracts

order-service consumes no events. It calls the internal APIs of cart-service, inventory-service and payment-service; the
shapes it relies on are pinned by its client adapters and their tests (`OrderContainersIT` exercises them over HTTP).
