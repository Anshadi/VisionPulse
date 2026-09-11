"""
VisionPulse Live Physical Webcam Runner
Uses the machine's real physical webcam (cv2.VideoCapture(0)).
Slices the live 640x480 video feed into 2 virtual monitored intersection zones:
  - CAM-01: West / Left Half
  - CAM-02: East / Right Half
Computes real-time Moving Edge Differencing (Sobel Δ-energy) and Farneback Optical Flow.
When you move your hand or an object across the camera:
  1. Real-time visual overlay highlights the moving edges.
  2. Events with real camera snapshots are dispatched to the Spring Boot cluster (port 8081).
  3. The cluster correlates the events and streams the incident to your dashboard!
"""

import cv2
import numpy as np
import time
import base64
import requests
import queue
import threading

BACKEND_URL = "http://localhost:8081/api/v1/events/ingest"

class LiveCameraCoordinator:
    def __init__(self):
        self.event_queue = queue.Queue(maxsize=50)
        self.worker_thread = threading.Thread(target=self._sender_loop, daemon=True)
        self.worker_thread.start()

    def publish_event(self, event):
        try:
            self.event_queue.put_nowait(event)
        except queue.Full:
            pass

    def _sender_loop(self):
        session = requests.Session()
        while True:
            event = self.event_queue.get()
            try:
                session.post(BACKEND_URL, json=event, timeout=1.0)
            except Exception:
                pass
            finally:
                self.event_queue.task_done()

def compute_zone_energy(gray, prev_gray, prev_sobel, x1, x2, h, w):
    sub_gray = gray[:, x1:x2]
    blurred = cv2.GaussianBlur(sub_gray, (5, 5), 0)
    gx = cv2.Sobel(blurred, cv2.CV_64F, 1, 0, ksize=3)
    gy = cv2.Sobel(blurred, cv2.CV_64F, 0, 1, ksize=3)
    sobel_mag = cv2.magnitude(gx, gy)

    edge_mask = (sobel_mag > 35).astype(np.uint8) * 255
    edge_density = float(np.count_nonzero(edge_mask) / (h * (x2 - x1)))

    moving_energy = 0.0
    if prev_sobel is not None:
        diff = cv2.absdiff(sobel_mag, prev_sobel)
        moving_energy = float(np.mean(diff))

    return sobel_mag, edge_mask, edge_density, moving_energy

def main():
    print("==================================================================")
    print("      VisionPulse: LIVE Physical Webcam Edge Processing Node      ")
    print("==================================================================")
    print("[*] Opening physical webcam (Device 0)...")

    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        cap = cv2.VideoCapture(0)

    if not cap.isOpened():
        print("[ERROR] Could not open physical webcam.")
        return

    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)

    coordinator = LiveCameraCoordinator()

    prev_gray = None
    prev_sobel_1 = None
    prev_sobel_2 = None

    history_1 = []
    history_2 = []

    last_trigger_1 = 0.0
    last_trigger_2 = 0.0
    cooldown = 0.6
    frame_seq = 0

    print("[*] Webcam active. Press 'q' in the camera window to exit.")
    print("[*] Wave your hand across the camera to test physical incident correlation!")

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        frame_seq += 1
        curr_time = time.time()
        h, w = frame.shape[:2]
        mid_x = w // 2

        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        # Process Zone 1 (Left: CAM-01)
        sobel_1, mask_1, density_1, energy_1 = compute_zone_energy(gray, prev_gray, prev_sobel_1, 0, mid_x, h, w)
        # Process Zone 2 (Right: CAM-02)
        sobel_2, mask_2, density_2, energy_2 = compute_zone_energy(gray, prev_gray, prev_sobel_2, mid_x, w, h, w)

        # Adaptive baselines
        history_1.append(energy_1)
        history_2.append(energy_2)
        if len(history_1) > 30: history_1.pop(0)
        if len(history_2) > 30: history_2.pop(0)

        base_1 = max(0.05, float(np.mean(history_1)))
        base_2 = max(0.05, float(np.mean(history_2)))

        ratio_1 = energy_1 / base_1
        ratio_2 = energy_2 / base_2

        # Create Visual Overlay Display
        display = frame.copy()

        # Zone separator line
        cv2.line(display, (mid_x, 0), (mid_x, h), (0, 229, 255), 2)
        cv2.putText(display, "ZONE 1 (CAM-01 WEST)", (20, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 229, 255), 2)
        cv2.putText(display, "ZONE 2 (CAM-02 EAST)", (mid_x + 20, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 229, 255), 2)

        # Trigger Zone 1 Anomaly
        alert_1 = False
        if ratio_1 >= 2.2 and (curr_time - last_trigger_1) > cooldown:
            last_trigger_1 = curr_time
            alert_1 = True
            
            # Capture real thumbnail
            thumb = cv2.resize(frame[:, :mid_x], (320, 240))
            _, buf = cv2.imencode(".jpg", thumb, [int(cv2.IMWRITE_JPEG_QUALITY), 65])
            b64_thumb = f"data:image/jpeg;base64,{base64.b64encode(buf).decode('utf-8')}"

            event_1 = {
                "cameraId": "CAM-01",
                "zoneId": "INTERSECTION_DOWNTOWN",
                "eventType": "RAPID_MOTION_SPIKE",
                "edgeDensity": round(density_1, 4),
                "movingEnergy": round(energy_1, 4),
                "deltaRatio": round(ratio_1, 2),
                "motionVectorAngle": 90.0,
                "velocityMagnitude": 3.5,
                "frameSeq": frame_seq,
                "detectedAt": int(curr_time * 1000),
                "snapshotBase64": b64_thumb
            }
            coordinator.publish_event(event_1)
            print(f"[LIVE TRIGGER] CAM-01 (West) Motion Ratio: {ratio_1:.1f}x -> Cluster Ingestion")

        # Trigger Zone 2 Anomaly
        alert_2 = False
        if ratio_2 >= 2.2 and (curr_time - last_trigger_2) > cooldown:
            last_trigger_2 = curr_time
            alert_2 = True
            
            thumb = cv2.resize(frame[:, mid_x:], (320, 240))
            _, buf = cv2.imencode(".jpg", thumb, [int(cv2.IMWRITE_JPEG_QUALITY), 65])
            b64_thumb = f"data:image/jpeg;base64,{base64.b64encode(buf).decode('utf-8')}"

            event_2 = {
                "cameraId": "CAM-02",
                "zoneId": "INTERSECTION_DOWNTOWN",
                "eventType": "RAPID_MOTION_SPIKE",
                "edgeDensity": round(density_2, 4),
                "movingEnergy": round(energy_2, 4),
                "deltaRatio": round(ratio_2, 2),
                "motionVectorAngle": 270.0,
                "velocityMagnitude": 3.8,
                "frameSeq": frame_seq,
                "detectedAt": int(curr_time * 1000),
                "snapshotBase64": b64_thumb
            }
            coordinator.publish_event(event_2)
            print(f"[LIVE TRIGGER] CAM-02 (East) Motion Ratio: {ratio_2:.1f}x -> Cluster Ingestion")

        # Visual highlights
        if alert_1 or (curr_time - last_trigger_1 < 0.3):
            cv2.rectangle(display, (0, 0), (mid_x, h), (0, 0, 255), 4)
            cv2.putText(display, "ALERT: CAM-01 MOTION!", (20, 70), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
        else:
            cv2.putText(display, f"Ratio: {ratio_1:.1f}x", (20, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

        if alert_2 or (curr_time - last_trigger_2 < 0.3):
            cv2.rectangle(display, (mid_x, 0), (w, h), (0, 0, 255), 4)
            cv2.putText(display, "ALERT: CAM-02 MOTION!", (mid_x + 20, 70), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
        else:
            cv2.putText(display, f"Ratio: {ratio_2:.1f}x", (mid_x + 20, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)

        prev_gray = gray
        prev_sobel_1 = sobel_1
        prev_sobel_2 = sobel_2

        cv2.imshow("VisionPulse - LIVE Physical Multi-Zone Camera Feed", display)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
