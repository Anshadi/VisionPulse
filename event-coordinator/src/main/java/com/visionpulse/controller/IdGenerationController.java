package com.visionpulse.controller;

import com.visionpulse.idgen.AdaptiveIdManager;
import com.visionpulse.model.IdResponse;
import com.visionpulse.model.ParsedIdDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/id")
@CrossOrigin(origins = "*")
public class IdGenerationController {

    private final AdaptiveIdManager idManager;

    @Autowired
    public IdGenerationController(AdaptiveIdManager idManager) {
        this.idManager = idManager;
    }

    @GetMapping("/next")
    public Mono<IdResponse> getNextId() {
        return idManager.generateId();
    }

    @GetMapping("/decode/{id}")
    public Mono<ParsedIdDto> decodeId(@PathVariable("id") long id) {
        return Mono.fromSupplier(() -> idManager.decodeId(id));
    }
}
