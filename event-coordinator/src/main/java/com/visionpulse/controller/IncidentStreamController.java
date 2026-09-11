package com.visionpulse.controller;

import com.visionpulse.model.CameraEvent;
import com.visionpulse.model.Incident;
import com.visionpulse.service.IncidentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class IncidentStreamController {

    private final IncidentService incidentService;

    @Autowired
    public IncidentStreamController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping(value = "/stream/incidents", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Incident> streamIncidents() {
        return incidentService.streamIncidents();
    }

    @GetMapping(value = "/stream/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<CameraEvent> streamEvents() {
        return incidentService.streamEvents();
    }

    @GetMapping("/incidents/recent")
    public List<Incident> getRecentIncidents() {
        return incidentService.getRecentIncidents();
    }
}
