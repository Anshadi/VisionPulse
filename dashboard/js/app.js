// VisionPulse Real-Time Dashboard Controller

let baseUrl = "http://localhost:8081";
let eventSourceIncidents = null;
let eventSourceEvents = null;

let totalEvents = 0;
let totalIncidents = 0;
let activeIncidentsCount = 0;
let waveformHistory = new Array(80).fill(10);
let incidentList = [];

// DOM Elements
const selectPort = document.getElementById("node-port-select");
const elStatusPill = document.getElementById("cluster-status-pill");
const elStatusText = document.getElementById("cluster-status-text");
const elEventsCount = document.getElementById("metric-events-count");
const elIncidentsCount = document.getElementById("metric-incidents-count");
const elActiveIncidents = document.getElementById("metric-active-incidents");
const elClusterLeader = document.getElementById("metric-cluster-leader");
const elP99Latency = document.getElementById("metric-p99-latency");
const elNodesContainer = document.getElementById("cluster-nodes-container");
const elCamerasGrid = document.getElementById("cameras-grid");
const elIncidentsFeed = document.getElementById("incidents-feed");
const elEmptyState = document.getElementById("empty-incidents-state");
const btnMock = document.getElementById("btn-trigger-mock");
const modal = document.getElementById("incident-modal");
const modalBody = document.getElementById("modal-incident-body");
const modalClose = document.getElementById("modal-close-btn");

// Canvas
const canvas = document.getElementById("waveform-canvas");
const ctx = canvas.getContext("2d");

// Initial Setup
document.addEventListener("DOMContentLoaded", () => {
    resizeCanvas();
    window.addEventListener("resize", resizeCanvas);

    selectPort.addEventListener("change", (e) => {
        baseUrl = e.target.value;
        restartStreams();
    });

    btnMock.addEventListener("click", triggerMockMultiCameraIncident);
    modalClose.addEventListener("click", () => modal.classList.remove("open"));

    initCameras();
    restartStreams();
    startPolling();
    requestAnimationFrame(renderWaveform);
});

function resizeCanvas() {
    canvas.width = canvas.parentElement.clientWidth;
    canvas.height = canvas.parentElement.clientHeight;
}

function initCameras() {
    const cameras = ["CAM-01", "CAM-02", "CAM-03", "CAM-04", "CAM-05", "CAM-06"];
    elCamerasGrid.innerHTML = "";
    cameras.forEach(cam => {
        const card = document.createElement("div");
        card.className = "camera-card";
        card.id = `card-${cam}`;
        card.innerHTML = `
            <div class="camera-card-top">
                <span class="camera-id">${cam}</span>
                <span class="cam-chip" id="owner-${cam}">Node-1</span>
            </div>
            <div class="energy-bar-wrap">
                <div class="energy-bar-fill" id="fill-${cam}"></div>
            </div>
            <div class="camera-metrics-sub">
                <span id="density-${cam}">Density: 0.12</span>
                <span id="status-${cam}">NORMAL</span>
            </div>
        `;
        elCamerasGrid.appendChild(card);
    });
}

function restartStreams() {
    if (eventSourceIncidents) eventSourceIncidents.close();
    if (eventSourceEvents) eventSourceEvents.close();

    // 1. Incidents SSE Stream
    try {
        eventSourceIncidents = new EventSource(`${baseUrl}/api/v1/stream/incidents`);
        eventSourceIncidents.onmessage = (e) => {
            const incident = JSON.parse(e.data);
            handleNewIncident(incident);
        };
        eventSourceIncidents.onerror = () => {
            elStatusPill.className = "cluster-status-pill danger";
            elStatusText.textContent = "Connecting to Node...";
        };
        eventSourceIncidents.onopen = () => {
            elStatusPill.className = "cluster-status-pill";
            elStatusText.textContent = "Cluster Synced (SSE Active)";
        };
    } catch (err) {
        console.error("SSE connection error", err);
    }

    // 2. Raw Events SSE Stream
    try {
        eventSourceEvents = new EventSource(`${baseUrl}/api/v1/stream/events`);
        eventSourceEvents.onmessage = (e) => {
            const event = JSON.parse(e.data);
            handleRawEvent(event);
        };
    } catch (err) {
        console.error("Events SSE error", err);
    }
}

function handleRawEvent(event) {
    totalEvents++;
    elEventsCount.textContent = totalEvents.toLocaleString();

    // Update camera card UI
    const camId = event.cameraId;
    const fill = document.getElementById(`fill-${camId}`);
    const density = document.getElementById(`density-${camId}`);
    const status = document.getElementById(`status-${camId}`);
    const card = document.getElementById(`card-${camId}`);

    if (fill && density) {
        const energyPercent = Math.min(100, Math.round(event.deltaRatio * 25));
        fill.style.width = `${energyPercent}%`;
        density.textContent = `Δ: ${event.deltaRatio}x`;

        waveformHistory.push(energyPercent);
        if (waveformHistory.length > 80) waveformHistory.shift();

        if (event.deltaRatio >= 2.0) {
            fill.classList.add("spike");
            card.classList.add("alert");
            status.textContent = "ANOMALY";
            status.style.color = "var(--accent-red)";
            setTimeout(() => {
                fill.classList.remove("spike");
                card.classList.remove("alert");
                status.textContent = "NORMAL";
                status.style.color = "var(--text-muted)";
            }, 800);
        }
    }
}

function handleNewIncident(incident) {
    totalIncidents++;
    activeIncidentsCount++;
    elIncidentsCount.textContent = totalIncidents.toLocaleString();
    elActiveIncidents.textContent = `${activeIncidentsCount} Active`;

    if (elEmptyState) elEmptyState.style.display = "none";

    incidentList.unshift(incident);

    const card = document.createElement("div");
    card.className = "incident-card";
    const dateStr = new Date(incident.firstEventTimestamp).toISOString().replace("T", " ").replace("Z", "");

    const camBadges = incident.triggeringCameras.map(c => `<span class="cam-chip" style="background: rgba(239, 68, 68, 0.2); color: #ef4444">${c}</span>`).join(" ");

    card.innerHTML = `
        <div class="incident-top">
            <span class="incident-id">INCIDENT #${incident.incidentId}</span>
            <span class="incident-conf">${Math.round(incident.confidenceScore * 100)}% MATCH</span>
        </div>
        <div class="incident-meta">
            <span>📍 ${incident.zoneId}</span>
            <span>⏱️ ${dateStr.split(" ")[1]}</span>
            <span>(${incident.durationMs}ms window)</span>
        </div>
        <div class="incident-cameras">
            ${camBadges}
        </div>
    `;

    card.addEventListener("click", () => showIncidentModal(incident));
    elIncidentsFeed.prepend(card);
}

function showIncidentModal(incident) {
    modalBody.innerHTML = `
        <pre>${JSON.stringify(incident, null, 2)}</pre>
    `;
    document.getElementById("modal-incident-title").textContent = `Incident #${incident.incidentId} Inspection`;
    modal.classList.add("open");
}

function startPolling() {
    setInterval(async () => {
        try {
            // 1. Cluster status
            const resStatus = await fetch(`${baseUrl}/api/v1/cluster/status`);
            if (resStatus.ok) {
                const nodeInfo = await resStatus.json();
                elClusterLeader.textContent = nodeInfo.leaderId;
                updateClusterTopology(nodeInfo);
            }

            // 2. Partitions
            const resPart = await fetch(`${baseUrl}/api/v1/cluster/partitions`);
            if (resPart.ok) {
                const partMap = await resPart.json();
                updatePartitionBadges(partMap);
            }

            // 3. Metrics
            const resMetrics = await fetch(`${baseUrl}/api/v1/metrics/latency`);
            if (resMetrics.ok) {
                const metrics = await resMetrics.json();
                if (metrics.p99Micros) {
                    elP99Latency.textContent = `${(metrics.p99Micros / 1000).toFixed(3)} ms`;
                }
            }
        } catch (e) {
            // backend may be momentarily starting
        }
    }, 1500);
}

function updateClusterTopology(nodeInfo) {
    elNodesContainer.innerHTML = `
        <div class="cluster-node-card ${nodeInfo.isLeader ? 'leader' : ''}">
            <div class="node-card-top">
                <span class="node-name">Node-${nodeInfo.nodeId}</span>
                <span class="node-role-badge ${nodeInfo.isLeader ? 'role-leader' : 'role-worker'}">${nodeInfo.role}</span>
            </div>
            <div style="font-size: 0.75rem; color: var(--text-secondary);">
                Uptime: ${nodeInfo.uptime} | Port: ${nodeInfo.port}
            </div>
            <div class="node-cameras-list">
                ${nodeInfo.assignedCameras.map(c => `<span class="cam-chip">${c}</span>`).join("")}
            </div>
        </div>
    `;
}

function updatePartitionBadges(partMap) {
    for (const [node, cams] of Object.entries(partMap)) {
        cams.forEach(cam => {
            const badge = document.getElementById(`owner-${cam}`);
            if (badge) badge.textContent = node;
        });
    }
}

async function triggerMockMultiCameraIncident() {
    const now = Date.now();
    const event1 = {
        cameraId: "CAM-01",
        zoneId: "INTERSECTION_DOWNTOWN",
        eventType: "RAPID_MOTION_SPIKE",
        edgeDensity: 0.68,
        movingEnergy: 1.45,
        deltaRatio: 3.2,
        frameSeq: 1042,
        detectedAt: now
    };

    const event2 = {
        cameraId: "CAM-02",
        zoneId: "INTERSECTION_DOWNTOWN",
        eventType: "RAPID_MOTION_SPIKE",
        edgeDensity: 0.72,
        movingEnergy: 1.58,
        deltaRatio: 3.6,
        frameSeq: 1044,
        detectedAt: now + 120 // 120ms later
    };

    try {
        await fetch(`${baseUrl}/api/v1/events/ingest`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(event1)
        });
        await fetch(`${baseUrl}/api/v1/events/ingest`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(event2)
        });
    } catch (e) {
        console.error("Failed to inject mock events", e);
    }
}

function renderWaveform() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const step = canvas.width / (waveformHistory.length - 1);
    ctx.beginPath();
    ctx.strokeStyle = "#00e5ff";
    ctx.lineWidth = 2;

    waveformHistory.forEach((val, i) => {
        const x = i * step;
        const y = canvas.height - (val / 100) * (canvas.height - 20) - 10;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
    });
    ctx.stroke();

    requestAnimationFrame(renderWaveform);
}
