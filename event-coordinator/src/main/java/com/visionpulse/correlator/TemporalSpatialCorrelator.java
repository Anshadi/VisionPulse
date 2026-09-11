package com.visionpulse.correlator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionpulse.config.AppProperties;
import com.visionpulse.idgen.AdaptiveIdManager;
import com.visionpulse.model.CameraEvent;
import com.visionpulse.model.Incident;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
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
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Local buffer per zoneId
    private final Map<String, List<CameraEvent>> zoneEventBuffers = new ConcurrentHashMap<>();
    
    // Real-time SSE Sinks
    private final Sinks.Many<Incident> incidentSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Sinks.Many<CameraEvent> eventSink = Sinks.many().multicast().onBackpressureBuffer();
    
    private final List<Incident> recentIncidents = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 200;

    @Autowired
    public TemporalSpatialCorrelator(
            AppProperties appProperties,
            AdaptiveIdManager idManager,
            Counter incidentCreatedCounter,
            ReactiveRedisTemplate<String, String> redisTemplate) {
        this.appProperties = appProperties;
        this.idManager = idManager;
        this.incidentCounter = incidentCreatedCounter;
        this.redisTemplate = redisTemplate;
    }

    public synchronized void processEvent(CameraEvent event) {
        eventSink.tryEmitNext(event);

        String zone = event.getZoneId() != null ? event.getZoneId() : "DEFAULT_ZONE";
        zoneEventBuffers.putIfAbsent(zone, new CopyOnWriteArrayList<>());
        List<CameraEvent> buffer = zoneEventBuffers.get(zone);

        buffer.add(event);

        // Async replicate to Redis Sorted Set with 10-second TTL for cluster-wide fault-tolerant state
        try {
            String redisKey = "visionpulse:sliding:" + zone;
            String jsonPayload = objectMapper.writeValueAsString(event);
            redisTemplate.opsForZSet().add(redisKey, jsonPayload, (double) event.getDetectedAt())
                    .flatMap(b -> redisTemplate.expire(redisKey, Duration.ofSeconds(10)))
                    .subscribe();
        } catch (Exception e) {
            // Redis replication non-critical fallback
        }

        long now = event.getDetectedAt();
        long windowMs = appProperties.getCorrelation().getWindowMs();
        int minCameras = appProperties.getCorrelation().getMinCamerasForIncident();

        // Evict stale events
        buffer.removeIf(e -> (now - e.getDetectedAt()) > (windowMs * 2));

        // Find events in sliding window
        List<CameraEvent> windowEvents = buffer.stream()
                .filter(e -> Math.abs(now - e.getDetectedAt()) <= windowMs)
                .collect(Collectors.toList());

        Set<String> uniqueCameras = windowEvents.stream()
                .map(CameraEvent::getCameraId)
                .collect(Collectors.toSet());

        if (uniqueCameras.size() >= minCameras) {
            long firstTime = windowEvents.stream().mapToLong(CameraEvent::getDetectedAt).min().orElse(now);
            long lastTime = windowEvents.stream().mapToLong(CameraEvent::getDetectedAt).max().orElse(now);
            
            double avgRatio = windowEvents.stream().mapToDouble(CameraEvent::getDeltaRatio).average().orElse(2.0);
            double confidence = Math.min(0.99, 0.60 + (uniqueCameras.size() * 0.12) + (Math.min(avgRatio, 5.0) * 0.04));

            // Extract primary snapshots per camera
            Map<String, String> snapshots = new HashMap<>();
            for (CameraEvent ev : windowEvents) {
                if (ev.getSnapshotBase64() != null && !snapshots.containsKey(ev.getCameraId())) {
                    snapshots.put(ev.getCameraId(), ev.getSnapshotBase64());
                }
            }

            long incidentId = idManager.generateRawId();

            Incident incident = Incident.builder()
                    .incidentId(incidentId)
                    .zoneId(zone)
                    .incidentType(classifyIncidentType(windowEvents))
                    .triggeringCameras(new ArrayList<>(uniqueCameras))
                    .eventCount(windowEvents.size())
                    .firstEventTimestamp(firstTime)
                    .lastEventTimestamp(lastTime)
                    .durationMs(lastTime - firstTime)
                    .confidenceScore(Math.round(confidence * 100.0) / 100.0)
                    .status("CORRELATED")
                    .coordinatorNodeId(appProperties.getNodeId())
                    .correlatedEvents(new ArrayList<>(windowEvents))
                    .cameraSnapshots(snapshots)
                    .build();

            log.info("*******************************************************************");
            log.info(">>> [INCIDENT CORRELATED] ID: #{} | Type: {} | Cameras: {} | Conf: {}% <<<", 
                    incidentId, incident.getIncidentType(), uniqueCameras, (int)(confidence * 100));
            log.info("*******************************************************************");

            incidentCounter.increment();
            recentIncidents.add(0, incident);
            if (recentIncidents.size() > MAX_HISTORY) {
                recentIncidents.remove(recentIncidents.size() - 1);
            }

            incidentSink.tryEmitNext(incident);
            buffer.removeAll(windowEvents);
        }
    }

    private String classifyIncidentType(List<CameraEvent> events) {
        boolean hasWrongWay = events.stream().anyMatch(e -> "WRONG_WAY_MOVEMENT".equals(e.getEventType()));
        if (hasWrongWay) return "WRONG_WAY_TRAFFIC_INCIDENT";
        boolean hasIntrusion = events.stream().anyMatch(e -> "RESTRICTED_ZONE_INTRUSION".equals(e.getEventType()));
        if (hasIntrusion) return "RESTRICTED_ZONE_VIOLATION";
        return "MULTI_CAMERA_TRAFFIC_ANOMALY";
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
