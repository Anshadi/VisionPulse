package com.visionpulse.service;

import com.visionpulse.config.AppProperties;
import com.visionpulse.correlator.TemporalSpatialCorrelator;
import com.visionpulse.idgen.AdaptiveIdManager;
import com.visionpulse.model.CameraEvent;
import com.visionpulse.model.IdResponse;
import com.visionpulse.model.Incident;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class IncidentService {

    private final AppProperties appProperties;
    private final AdaptiveIdManager idManager;
    private final TemporalSpatialCorrelator correlator;

    @Autowired
    public IncidentService(
            AppProperties appProperties,
            AdaptiveIdManager idManager,
            TemporalSpatialCorrelator correlator) {
        this.appProperties = appProperties;
        this.idManager = idManager;
        this.correlator = correlator;
    }

    public Mono<CameraEvent> ingestEvent(CameraEvent rawEvent) {
        return idManager.generateId()
                .map(idResp -> {
                    rawEvent.setEventId(idResp.getId());
                    rawEvent.setProcessedByNodeId(appProperties.getNodeId());
                    rawEvent.setStrategyUsed(idResp.getStrategy());
                    if (rawEvent.getDetectedAt() <= 0) {
                        rawEvent.setDetectedAt(System.currentTimeMillis());
                    }

                    // Feed to sliding-window correlator
                    correlator.processEvent(rawEvent);

                    return rawEvent;
                });
    }

    public Flux<Incident> streamIncidents() {
        return correlator.getIncidentStream();
    }

    public Flux<CameraEvent> streamEvents() {
        return correlator.getEventStream();
    }

    public List<Incident> getRecentIncidents() {
        return correlator.getRecentIncidents();
    }
}
