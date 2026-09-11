// VisionPulse Real-Time Dashboard Controller (Enhanced with LIVE Physical Webcam & Sobel Convolution)

let baseUrl = "http://localhost:8081";
let eventSourceIncidents = null;
let eventSourceEvents = null;

let totalEvents = 0;
let totalIncidents = 0;
let activeIncidentsCount = 0;
let waveformHistory = new Array(80).fill(10);
let incidentList = [];

// Webcam state
let isWebcamRunning = false;
let webcamStream = null;
let prevSobel1 = null;
let prevSobel2 = null;
let history1 = [];
let history2 = [];
let lastTrigger1 = 0;
let lastTrigger2 = 0;
let frameSeq = 0;

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

const btnWebcam = document.getElementById("btn-toggle-webcam");
const stripWebcam = document.getElementById("webcam-live-strip");
const videoEl = document.getElementById("webcam-video");
const sobelLiveCanvas = document.getElementById("canvas-sobel-live");
const sobelLiveCtx = sobelLiveCanvas.getContext("2d");
const offscreenCanvas = document.getElementById("offscreen-canvas");
const offscreenCtx = offscreenCanvas.getContext("2d", { willReadFrequently: true });

const btnSpeed = document.getElementById("btn-mock-speed");
const btnWrongWay = document.getElementById("btn-mock-wrongway");
const btnIntrusion = document.getElementById("btn-mock-intrusion");

const modal = document.getElementById("incident-modal");
const modalBody = document.getElementById("modal-incident-body");
const modalClose = document.getElementById("modal-close-btn");

// Waveform Canvas
const canvas = document.getElementById("waveform-canvas");
const ctx = canvas.getContext("2d");

// Setup
document.addEventListener("DOMContentLoaded", () => {
    resizeCanvas();
    window.addEventListener("resize", resizeCanvas);

    selectPort.addEventListener("change", (e) => {
        baseUrl = e.target.value;
        restartStreams();
    });

    if (btnWebcam) btnWebcam.addEventListener("click", toggleLiveWebcam);
    if (btnSpeed) btnSpeed.addEventListener("click", () => triggerMockIncident("RAPID_SPEEDING_ANOMALY", 3.2, 90.0));
    if (btnWrongWay) btnWrongWay.addEventListener("click", () => triggerMockIncident("WRONG_WAY_MOVEMENT", 3.8, 270.0));
    if (btnIntrusion) btnIntrusion.addEventListener("click", () => triggerMockIncident("RESTRICTED_ZONE_INTRUSION", 4.5, 45.0));

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
                <span id="density-${cam}">Δ: 1.0x</span>
                <span id="status-${cam}">NORMAL</span>
            </div>
        `;
        elCamerasGrid.appendChild(card);
    });
}

// ==================== REAL-TIME PHYSICAL WEBCAM PIPELINE ====================
async function toggleLiveWebcam() {
    if (isWebcamRunning) {
        stopWebcam();
    } else {
        await startWebcam();
    }
}

async function startWebcam() {
    try {
        webcamStream = await navigator.mediaDevices.getUserMedia({
            video: { width: 320, height: 240, facingMode: "user" }
        });
        videoEl.srcObject = webcamStream;
        await videoEl.play();

        isWebcamRunning = true;
        btnWebcam.textContent = "🛑 Stop Live Webcam";
        btnWebcam.style.background = "linear-gradient(135deg, #ef4444, #b91c1c)";
        stripWebcam.style.display = "flex";

        requestAnimationFrame(processWebcamFrame);
    } catch (err) {
        alert("Could not access webcam: " + err.message + "\nPlease allow camera permissions.");
    }
}

function stopWebcam() {
    if (webcamStream) {
        webcamStream.getTracks().forEach(t => t.stop());
    }
    isWebcamRunning = false;
    btnWebcam.textContent = "🎥 Start Live Webcam Feed";
    btnWebcam.style.background = "";
    stripWebcam.style.display = "none";
}

function processWebcamFrame() {
    if (!isWebcamRunning) return;

    frameSeq++;
    const now = Date.now();
    const w = 320;
    const h = 240;
    const midX = w / 2;

    offscreenCtx.drawImage(videoEl, 0, 0, w, h);
    const frameData = offscreenCtx.getImageData(0, 0, w, h);
    const pixels = frameData.data;

    // Convert to grayscale & compute Sobel gradients
    const gray = new Uint8Array(w * h);
    for (let i = 0; i < pixels.length; i += 4) {
        gray[i / 4] = 0.299 * pixels[i] + 0.587 * pixels[i + 1] + 0.114 * pixels[i + 2];
    }

    const sobelOutput = sobelLiveCtx.createImageData(w, h);
    const sobelPixels = sobelOutput.data;

    let energy1 = 0;
    let energy2 = 0;
    const currentSobel1 = new Float32Array((w / 2) * h);
    const currentSobel2 = new Float32Array((w / 2) * h);

    // Sobel 3x3 Convolution
    for (let y = 1; y < h - 1; y++) {
        for (let x = 1; x < w - 1; x++) {
            const idx = y * w + x;

            // Gx
            const gx = 
                -gray[(y - 1) * w + (x - 1)] + gray[(y - 1) * w + (x + 1)] +
                -2 * gray[y * w + (x - 1)]   + 2 * gray[y * w + (x + 1)] +
                -gray[(y + 1) * w + (x - 1)] + gray[(y + 1) * w + (x + 1)];

            // Gy
            const gy = 
                -gray[(y - 1) * w + (x - 1)] - 2 * gray[(y - 1) * w + x] - gray[(y - 1) * w + (x + 1)] +
                 gray[(y + 1) * w + (x - 1)] + 2 * gray[(y + 1) * w + x] + gray[(y + 1) * w + (x + 1)];

            const mag = Math.min(255, Math.sqrt(gx * gx + gy * gy));

            // Render edge visualization (Cyan glow)
            const pIdx = idx * 4;
            if (mag > 35) {
                sobelPixels[pIdx] = 0;         // R
                sobelPixels[pIdx + 1] = 229;   // G
                sobelPixels[pIdx + 2] = 255;   // B
                sobelPixels[pIdx + 3] = 255;   // Alpha
            } else {
                sobelPixels[pIdx] = 16;
                sobelPixels[pIdx + 1] = 21;
                sobelPixels[pIdx + 2] = 38;
                sobelPixels[pIdx + 3] = 255;
            }

            // Distribute energy to Zone 1 (Left / CAM-01) or Zone 2 (Right / CAM-02)
            if (x < midX) {
                const subIdx = y * (w / 2) + x;
                currentSobel1[subIdx] = mag;
                if (prevSobel1) {
                    energy1 += Math.abs(mag - prevSobel1[subIdx]);
                }
            } else {
                const subIdx = y * (w / 2) + (x - midX);
                currentSobel2[subIdx] = mag;
                if (prevSobel2) {
                    energy2 += Math.abs(mag - prevSobel2[subIdx]);
                }
            }
        }
    }

    sobelLiveCtx.putImageData(sobelOutput, 0, 0);

    // Normalize moving energy
    const halfArea = (w / 2) * h;
    const avgEnergy1 = energy1 / halfArea;
    const avgEnergy2 = energy2 / halfArea;

    history1.push(avgEnergy1);
    history2.push(avgEnergy2);
    if (history1.length > 25) history1.shift();
    if (history2.length > 25) history2.shift();

    const base1 = Math.max(0.2, history1.reduce((a, b) => a + b, 0) / history1.length);
    const base2 = Math.max(0.2, history2.reduce((a, b) => a + b, 0) / history2.length);

    const ratio1 = avgEnergy1 / base1;
    const ratio2 = avgEnergy2 / base2;

    // Update real-time camera cards
    updateLiveCameraCard("CAM-01", ratio1);
    updateLiveCameraCard("CAM-02", ratio2);

    // Check Anomaly Spikes (> 2.3x baseline)
    const cooldown = 600; // ms
    if (ratio1 >= 2.3 && (now - lastTrigger1) > cooldown) {
        lastTrigger1 = now;
        const snapshot1 = offscreenCanvas.toDataURL("image/jpeg", 0.65);
        sendPhysicalWebcamEvent("CAM-01", ratio1, 90.0, snapshot1);
    }

    if (ratio2 >= 2.3 && (now - lastTrigger2) > cooldown) {
        lastTrigger2 = now;
        const snapshot2 = offscreenCanvas.toDataURL("image/jpeg", 0.65);
        sendPhysicalWebcamEvent("CAM-02", ratio2, 270.0, snapshot2);
    }

    prevSobel1 = currentSobel1;
    prevSobel2 = currentSobel2;

    requestAnimationFrame(processWebcamFrame);
}

function updateLiveCameraCard(camId, ratio) {
    const fill = document.getElementById(`fill-${camId}`);
    const density = document.getElementById(`density-${camId}`);
    const status = document.getElementById(`status-${camId}`);
    const card = document.getElementById(`card-${camId}`);

    if (fill && density) {
        const energyPercent = Math.min(100, Math.round(ratio * 25));
        fill.style.width = `${energyPercent}%`;
        density.textContent = `Δ: ${ratio.toFixed(1)}x (LIVE)`;

        waveformHistory.push(energyPercent);
        if (waveformHistory.length > 80) waveformHistory.shift();

        if (ratio >= 2.3) {
            fill.classList.add("spike");
            card.classList.add("alert");
            status.textContent = "MOTION ALERT";
            status.style.color = "var(--accent-red)";
            setTimeout(() => {
                fill.classList.remove("spike");
                card.classList.remove("alert");
                status.textContent = "NORMAL";
                status.style.color = "var(--text-muted)";
            }, 600);
        }
    }
}

async function sendPhysicalWebcamEvent(camId, ratio, heading, snapshotB64) {
    const payload = {
        cameraId: camId,
        zoneId: "INTERSECTION_DOWNTOWN",
        eventType: "PHYSICAL_WEBCAM_MOTION",
        edgeDensity: 0.78,
        movingEnergy: roundVal(ratio * 1.2),
        deltaRatio: roundVal(ratio),
        motionVectorAngle: heading,
        velocityMagnitude: 4.0,
        frameSeq: frameSeq,
        detectedAt: Date.now(),
        snapshotBase64: snapshotB64
    };

    try {
        await fetch(`${baseUrl}/api/v1/events/ingest`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
    } catch (e) {
        console.error("Failed to post physical webcam event", e);
    }
}

function roundVal(v) { return Math.round(v * 100) / 100; }

// ==================== REAL-TIME STREAMS & DASHBOARD CONTROLS ====================

function restartStreams() {
    if (eventSourceIncidents) eventSourceIncidents.close();
    if (eventSourceEvents) eventSourceEvents.close();

    // 1. Incidents SSE
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
        console.error("SSE Incident error", err);
    }

    // 2. Raw Events SSE
    try {
        eventSourceEvents = new EventSource(`${baseUrl}/api/v1/stream/events`);
        eventSourceEvents.onmessage = (e) => {
            const event = JSON.parse(e.data);
            handleRawEvent(event);
        };
    } catch (err) {
        console.error("SSE Events error", err);
    }
}

function handleRawEvent(event) {
    totalEvents++;
    elEventsCount.textContent = totalEvents.toLocaleString();

    // If live webcam is not controlling this card, update from server stream
    if (!isWebcamRunning || (event.cameraId !== "CAM-01" && event.cameraId !== "CAM-02")) {
        const camId = event.cameraId;
        const fill = document.getElementById(`fill-${camId}`);
        const density = document.getElementById(`density-${camId}`);
        const status = document.getElementById(`status-${camId}`);
        const card = document.getElementById(`card-${camId}`);

        if (fill && density) {
            const energyPercent = Math.min(100, Math.round(event.deltaRatio * 25));
            fill.style.width = `${energyPercent}%`;
            density.textContent = `Δ: ${event.deltaRatio}x | ${event.motionVectorAngle || 0}°`;

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

    const camBadges = incident.triggeringCameras.map(c => 
        `<span class="cam-chip" style="background: rgba(239, 68, 68, 0.2); color: #ef4444">${c}</span>`
    ).join(" ");

    let thumbHtml = "";
    if (incident.cameraSnapshots && Object.keys(incident.cameraSnapshots).length > 0) {
        const thumbs = Object.entries(incident.cameraSnapshots).map(([cam, src]) => `
            <img class="incident-thumb" src="${src}" alt="Snapshot ${cam}" title="Real Snapshot from ${cam}" />
        `).join("");
        thumbHtml = `<div class="incident-thumbnails-row">${thumbs}</div>`;
    }

    card.innerHTML = `
        <div class="incident-top">
            <span class="incident-id">INCIDENT #${incident.incidentId}</span>
            <span class="incident-conf">${Math.round(incident.confidenceScore * 100)}% MATCH</span>
        </div>
        <div class="incident-meta">
            <span>📍 ${incident.zoneId}</span>
            <span>🚨 ${incident.incidentType}</span>
            <span>⏱️ ${dateStr.split(" ")[1]} (${incident.durationMs}ms)</span>
        </div>
        ${thumbHtml}
        <div class="incident-cameras">
            ${camBadges}
        </div>
    `;

    card.addEventListener("click", () => showIncidentModal(incident));
    elIncidentsFeed.prepend(card);
}

function showIncidentModal(incident) {
    let snapshotsGrid = "";
    if (incident.cameraSnapshots && Object.keys(incident.cameraSnapshots).length > 0) {
        snapshotsGrid = `
            <div class="sub-section-title"><h3>Multi-Camera Synchronized Visual Replay (Live Captured)</h3></div>
            <div class="modal-snapshots-grid">
                ${Object.entries(incident.cameraSnapshots).map(([cam, src]) => `
                    <div class="modal-snapshot-card">
                        <span style="font-size:0.75rem; font-weight:700; color:var(--accent-cyan);">${cam} View</span>
                        <img src="${src}" alt="${cam} Replay" />
                    </div>
                `).join("")}
            </div>
        `;
    }

    modalBody.innerHTML = `
        ${snapshotsGrid}
        <div style="margin-top: 12px;" class="sub-section-title"><h3>Correlated Event Metadata & Ordering</h3></div>
        <pre>${JSON.stringify(incident, null, 2)}</pre>
    `;
    document.getElementById("modal-incident-title").textContent = `Incident #${incident.incidentId} Inspection`;
    modal.classList.add("open");
}

function startPolling() {
    setInterval(async () => {
        try {
            const resStatus = await fetch(`${baseUrl}/api/v1/cluster/status`);
            if (resStatus.ok) {
                const nodeInfo = await resStatus.json();
                elClusterLeader.textContent = nodeInfo.leaderId;
                updateClusterTopology(nodeInfo);
            }

            const resPart = await fetch(`${baseUrl}/api/v1/cluster/partitions`);
            if (resPart.ok) {
                const partMap = await resPart.json();
                updatePartitionBadges(partMap);
            }

            const resMetrics = await fetch(`${baseUrl}/api/v1/metrics/latency`);
            if (resMetrics.ok) {
                const metrics = await resMetrics.json();
                if (metrics.p99Micros) {
                    elP99Latency.textContent = `${(metrics.p99Micros / 1000).toFixed(3)} ms`;
                }
            }
        } catch (e) {
            // ignore momentary rebalance
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

function createMockSnapshot(camId, type) {
    const c = document.createElement("canvas");
    c.width = 320;
    c.height = 240;
    const cx = c.getContext("2d");
    cx.fillStyle = "#1e293b";
    cx.fillRect(0, 0, 320, 240);
    cx.fillStyle = "#334155";
    cx.fillRect(60, 0, 200, 240);
    cx.strokeStyle = "#ef4444";
    cx.lineWidth = 3;
    cx.strokeRect(90, 80, 70, 90);
    cx.fillStyle = "#ef4444";
    cx.font = "bold 14px Outfit";
    cx.fillText(`ALERT: ${type}`, 20, 30);
    cx.fillStyle = "#00e5ff";
    cx.font = "12px JetBrains Mono";
    cx.fillText(`${camId} VIEW | SIMULATED REPLAY`, 20, 220);
    return c.toDataURL("image/jpeg", 0.7);
}

async function triggerMockIncident(eventType, ratio, heading) {
    const now = Date.now();
    const event1 = {
        cameraId: "CAM-01",
        zoneId: "INTERSECTION_DOWNTOWN",
        eventType: eventType,
        edgeDensity: 0.75,
        movingEnergy: 1.85,
        deltaRatio: ratio,
        motionVectorAngle: heading,
        velocityMagnitude: 4.2,
        frameSeq: 2041,
        detectedAt: now,
        snapshotBase64: createMockSnapshot("CAM-01", eventType)
    };

    const event2 = {
        cameraId: "CAM-02",
        zoneId: "INTERSECTION_DOWNTOWN",
        eventType: eventType,
        edgeDensity: 0.81,
        movingEnergy: 1.95,
        deltaRatio: ratio + 0.3,
        motionVectorAngle: heading,
        velocityMagnitude: 4.6,
        frameSeq: 2043,
        detectedAt: now + 140,
        snapshotBase64: createMockSnapshot("CAM-02", eventType)
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
        console.error("Failed to inject incident", e);
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
