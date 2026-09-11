"""
VisionPulse High-Throughput Load Generator & Benchmarking Tool
Fires thousands of concurrent synthetic vision anomaly events to test ID throughput and correlator latency.
"""

import time
import json
import random
import requests
from concurrent.futures import ThreadPoolExecutor

TARGET_URL = "http://localhost:8081/api/v1/events/ingest"
TOTAL_EVENTS = 5000
CONCURRENCY = 50

CAMERAS = ["CAM-01", "CAM-02", "CAM-03", "CAM-04", "CAM-05", "CAM-06"]
ZONES = ["INTERSECTION_DOWNTOWN", "INTERSECTION_UPTOWN", "HIGHWAY_GATE_A"]

latencies = []

def send_event(session, event_id):
    cam = random.choice(CAMERAS)
    zone = random.choice(ZONES)
    ratio = random.uniform(1.2, 4.5)
    
    payload = {
        "cameraId": cam,
        "zoneId": zone,
        "eventType": "RAPID_MOTION_SPIKE" if ratio > 2.5 else "MOVING_EDGE_ANOMALY",
        "edgeDensity": round(random.uniform(0.2, 0.8), 4),
        "movingEnergy": round(random.uniform(0.5, 2.5), 4),
        "deltaRatio": round(ratio, 2),
        "frameSeq": event_id,
        "detectedAt": int(time.time() * 1000)
    }

    t0 = time.perf_counter()
    try:
        resp = session.post(TARGET_URL, json=payload, timeout=2.0)
        t1 = time.perf_counter()
        lat_ms = (t1 - t0) * 1000.0
        return lat_ms, resp.status_code == 200
    except Exception as e:
        return 0, False

def run_benchmark():
    print("==================================================================")
    print(f" VisionPulse Stress Benchmark: {TOTAL_EVENTS} Events @ {CONCURRENCY} Workers")
    print(f" Target Endpoint: {TARGET_URL}")
    print("==================================================================")

    session = requests.Session()
    adapter = requests.adapters.HTTPAdapter(pool_connections=CONCURRENCY, pool_maxsize=CONCURRENCY)
    session.mount("http://", adapter)

    start_wall = time.time()
    success_count = 0
    
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as executor:
        futures = [executor.submit(send_event, session, i) for i in range(TOTAL_EVENTS)]
        for f in futures:
            lat_ms, ok = f.result()
            if ok:
                success_count += 1
                latencies.append(lat_ms)

    total_time = time.time() - start_wall
    throughput = TOTAL_EVENTS / total_time

    latencies.sort()
    p50 = latencies[int(len(latencies) * 0.50)] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    p99 = latencies[int(len(latencies) * 0.99)] if latencies else 0

    print("\n------------------ BENCHMARK RESULTS ------------------")
    print(f" Total Events Sent    : {TOTAL_EVENTS}")
    print(f" Successful Ingests   : {success_count} ({success_count/TOTAL_EVENTS*100:.1f}%)")
    print(f" Wall Clock Time      : {total_time:.2f} seconds")
    print(f" Throughput (Req/Sec) : {throughput:.1f} events/sec")
    print(f" Latency p50          : {p50:.2f} ms")
    print(f" Latency p95          : {p95:.2f} ms")
    print(f" Latency p99          : {p99:.2f} ms")
    print("-------------------------------------------------------")

if __name__ == "__main__":
    run_benchmark()
