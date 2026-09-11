package com.visionpulse.controller;

import com.visionpulse.model.CameraEvent;
import com.visionpulse.service.IncidentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/events")
@CrossOrigin(origins = "*")
public class EventIngestionController {

    private final IncidentService incidentService;

    @Autowired
    public EventIngestionController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CameraEvent> ingestEvent(@RequestBody CameraEvent rawEvent) {
        return incidentService.ingestEvent(rawEvent);
    }
}
