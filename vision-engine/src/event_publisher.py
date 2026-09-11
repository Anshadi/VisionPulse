"""
Event Publisher: Dispatches JSON Anomaly Events to Backend Ingestion Service
Uses non-blocking worker queue to prevent video processing drops.
"""

import requests
import json
import queue
import threading
import time
from typing import Dict, Any

class EventPublisher:
    def __init__(self, backend_url: str = "http://localhost:8081/api/v1/events/ingest"):
        self.backend_url = backend_url
        self.queue = queue.Queue(maxsize=10000)
        self.running = True
        self.worker_thread = threading.Thread(target=self._worker, daemon=True)
        self.worker_thread.start()

    def publish(self, event: Dict[str, Any]):
        try:
            self.queue.put_nowait(event)
        except queue.Full:
            print("[WARN] Event queue full. Dropping event to maintain real-time edge processing.")

    def _worker(self):
        session = requests.Session()
        headers = {"Content-Type": "application/json"}

        while self.running:
            try:
                event = self.queue.get(timeout=0.2)
            except queue.Empty:
                continue

            try:
                resp = session.post(self.backend_url, json=event, headers=headers, timeout=1.5)
                if resp.status_code == 200:
                    data = resp.json()
                    # print(f"[EVENT SENT] Camera {event['cameraId']} -> Event ID: {data.get('eventId')}")
                else:
                    print(f"[WARN] Ingestion returned status {resp.status_code}")
            except Exception as e:
                # Backend might be failing over or offline
                # print(f"[DEBUG] Failed to post event: {e}")
                pass
            finally:
                self.queue.task_done()

    def stop(self):
        self.running = False
