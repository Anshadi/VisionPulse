package com.visionpulse.correlator;

import com.visionpulse.config.AppProperties;
import com.visionpulse.idgen.AdaptiveIdManager;
import com.visionpulse.model.CameraEvent;
import com.visionpulse.model.Incident;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class TemporalSpatialCorrelator {

    private static final Logger log = LoggerFactory.getLogger(TemporalSpatialCorrelator.class);

    private final AppProperties appProperties;
    private final AdaptiveIdManager idManager;
    private final Counter incidentCounter;

    // Buffer per zoneId: list of recent CameraEvents
    private final Map<String, List<CameraEvent>> zoneEventBuffers = new ConcurrentHashMap<>();
    
    // Sinks for real-time SSE push
    private final Sinks.Many<Incident> incidentSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Sinks.Many<CameraEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer();
    
    // In-memory recent incident cache (circular buffer / max 200)
    private final List<Incident> recentIncidents = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 200;

    @Autowired
    public TemporalSpatialCorrelator(
            AppProperties appProperties,
            AdaptiveIdManager idManager,
            Counter incidentCreatedCounter) {
        this.appProperties = appProperties;
        this.idManager = idManager;
        this.incidentCounter = incidentCreatedCounter;
    }

    public synchronized void processEvent(CameraEvent event) {
        // Emit raw event to stream
        eventSink.tryEmitNext(event);

        String zone = event.getZoneId() != null ? event.getZoneId() : "DEFAULT_ZONE";
        zoneEventBuffers.putIfAbsent(zone, new CopyOnWriteArrayList<>());
        List<CameraEvent> buffer = zoneEventBuffers.get(zone);

        buffer.add(event);

        long now = event.getDetectedAt();
        long windowMs = appProperties.getCorrelation().getWindowMs();
        int minCameras = appProperties.getCorrelation().getMinCamerasForIncident();

        // Evict events outside sliding window
        buffer.removeIf(e -> (now - e.getDetectedAt()) > (windowMs * 2));

        // Find events in the current window [now - windowMs, now]
        List<CameraEvent> windowEvents = buffer.stream()
                .filter(e -> Math.abs(now - e.getDetectedAt()) <= windowMs)
                .collect(Collectors.toList());

        Set<String> uniqueCameras = windowEvents.stream()
                .map(CameraEvent::getCameraId)
                .collect(Collectors.toSet());

        if (uniqueCameras.size() >= minCameras) {
            // Multiple cameras detected physical anomaly within the time window!
            long firstTime = windowEvents.stream().mapToLong(CameraEvent::getDetectedAt).min().orElse(now);
            long lastTime = windowEvents.stream().mapToLong(CameraEvent::getDetectedAt).max().orElse(now);
            
            double avgRatio = windowEvents.stream().mapToDouble(CameraEvent::getDeltaRatio).average().orElse(2.0);
            double confidence = Math.min(0.99, 0.60 + (uniqueCameras.size() * 0.12) + (Math.min(avgRatio, 5.0) * 0.04));

            long incidentId = idManager.generateRawId();

            Incident incident = Incident.builder()
                    .incidentId(incidentId)
                    .zoneId(zone)
                    .incidentType("MULTI_CAMERA_TRAFFIC_ANOMALY")
                    .triggeringCameras(new ArrayList<>(uniqueCameras))
                    .eventCount(windowEvents.size())
                    .firstEventTimestamp(firstTime)
                    .lastEventTimestamp(lastTime)
                    .durationMs(lastTime - firstTime)
                    .confidenceScore(Math.round(confidence * 100.0) / 100.0)
                    .status("CORRELATED")
                    .coordinatorNodeId(appProperties.getNodeId())
                    .correlatedEvents(new ArrayList<>(windowEvents))
                    .build();

            log.info("*******************************************************************");
            log.info(">>> [INCIDENT CORRELATED] ID: #{} | Zone: {} | Cameras: {} | Conf: {}% <<<", 
                    incidentId, zone, uniqueCameras, (int)(confidence * 100));
            log.info("*******************************************************************");

            incidentCounter.increment();
            recentIncidents.add(0, incident);
            if (recentIncidents.size() > MAX_HISTORY) {
                recentIncidents.remove(recentIncidents.size() - 1);
            }

            incidentSink.tryEmitNext(incident);

            // Clear the correlated events from buffer to prevent duplicate incident creation for the same trigger
            buffer.removeAll(windowEvents);
        }
    }

    public Flux<Incident> getIncidentStream() {
        return incidentSink.asFlux();
    }

    public Flux<CameraEvent> getEventStream() {
        return eventSink.asFlux();
    }

    public List<Incident> getRecentIncidents() {
        return Collections.unmodifiableList(recentIncidents);
    }
}
