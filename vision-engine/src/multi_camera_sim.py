"""
Enhanced Multi-Camera Synchronized Intersection Simulator
Simulates 3 cameras (CAM-01 Northbound, CAM-02 Southbound, CAM-03 Eastbound) monitoring an intersection.
Generates realistic moving edge anomalies, optical flow headings, and visual snapshot thumbnails.
"""

import time
import threading
from detector import MovingEdgeDetector
from camera_streamer import CameraStreamer
from event_publisher import EventPublisher

def run_camera_worker(camera_id: str, zone_id: str, heading: float, publisher: EventPublisher):
    print(f"[*] Starting Vision Edge Node for {camera_id} (Expected Heading: {heading} deg)...")
    streamer = CameraStreamer(source="synthetic", fps=30)
    detector = MovingEdgeDetector(
        camera_id=camera_id,
        zone_id=zone_id,
        spike_multiplier=1.6,
        expected_heading_deg=heading
    )

    for frame in streamer.frames():
        vis, event = detector.process_frame(frame)
        if event:
            print(f"[EDGE TRIGGER] {camera_id} -> {event['eventType']} | Ratio: {event['deltaRatio']}x | Heading: {event['motionVectorAngle']} deg")
            publisher.publish(event)

    streamer.release()

def main():
    print("==================================================================")
    print(" VisionPulse Edge Vision: Multi-Camera Directional & ROI Engine   ")
    print("==================================================================")
    publisher = EventPublisher(backend_url="http://localhost:8081/api/v1/events/ingest")

    cameras = [
        ("CAM-01", "INTERSECTION_DOWNTOWN", 90.0),   # Southbound
        ("CAM-02", "INTERSECTION_DOWNTOWN", 270.0),  # Northbound
        ("CAM-03", "INTERSECTION_DOWNTOWN", 0.0)     # Eastbound
    ]

    threads = []
    for cam_id, zone_id, heading in cameras:
        t = threading.Thread(target=run_camera_worker, args=(cam_id, zone_id, heading, publisher), daemon=True)
        threads.append(t)
        t.start()
        time.sleep(0.1)

    print("[*] All 3 camera pipelines active with Optical Flow & Snapshot generation.")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Stopping vision engine...")
        publisher.stop()

if __name__ == "__main__":
    main()
