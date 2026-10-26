package com.eluno.pos.controller;

import com.eluno.pos.service.PosService;
import com.eluno.pos.client.ApiClient.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PosController {
    private final PosService pos;

    public PosController(PosService p) {
        pos = p;
    }

    @GetMapping("/health")
    Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @GetMapping("/customers/lookup")
    JsonNode customer(@RequestParam String phone) {
        return pos.customer(phone);
    }

    @GetMapping("/products/{type}")
    JsonNode products(@PathVariable String type) {
        return pos.products(type.equals("frames") ? "Frame" : "Lens");
    }

    @PostMapping("/cart/validate")
    JsonNode validate(@RequestBody JsonNode body) {
        return pos.preview(body);
    }

    @PostMapping("/checkout/preview")
    JsonNode preview(@RequestBody JsonNode body) {
        return pos.preview(body);
    }

    @PostMapping("/checkout")
    JsonNode checkout(@RequestBody JsonNode body) {
        return pos.checkout(body);
    }

    @PostMapping("/refunds/{id}/full")
    JsonNode refund(@PathVariable long id, @RequestBody(required = false) JsonNode body) {
        return pos.refund(id, body == null ? "CUSTOMER_REQUEST" : body.path("reason_code").asText("CUSTOMER_REQUEST"));
    }

    @GetMapping("/reports/day-end")
    JsonNode report(@RequestParam long locationId, @RequestParam String date) {
        return pos.report(locationId, date);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> error(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }
}
