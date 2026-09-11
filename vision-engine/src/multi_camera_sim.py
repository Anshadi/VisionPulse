"""
Multi-Camera Synchronized Intersection Simulator
Simulates 3 cameras (CAM-01 North, CAM-02 South, CAM-03 East) monitoring a common intersection.
When an incident happens, CAM-01 and CAM-02 detect anomalies within 300ms, triggering correlation.
"""

import cv2
import time
import threading
from detector import MovingEdgeDetector
from camera_streamer import CameraStreamer
from event_publisher import EventPublisher

def run_camera_worker(camera_id: str, zone_id: str, publisher: EventPublisher):
    print(f"[*] Starting Vision Edge Node for {camera_id} in {zone_id}...")
    streamer = CameraStreamer(source="synthetic", fps=30)
    detector = MovingEdgeDetector(camera_id=camera_id, zone_id=zone_id, spike_multiplier=1.7)

    for frame in streamer.frames():
        vis, event = detector.process_frame(frame)
        if event:
            print(f"[EDGE TRIGGER] {camera_id} @ {event['detectedAt']} | Ratio: {event['deltaRatio']}x -> Publishing to Cluster")
            publisher.publish(event)
        
        # Display simulated vision overlay if running interactively
        # cv2.imshow(f"VisionPulse - {camera_id}", vis)
        # if cv2.waitKey(1) & 0xFF == ord('q'):
        #     break

    streamer.release()

def main():
    print("==================================================================")
    print(" VisionPulse Edge Vision Simulator: 3-Camera Intersection Active  ")
    print("==================================================================")
    publisher = EventPublisher(backend_url="http://localhost:8081/api/v1/events/ingest")

    threads = []
    cameras = [
        ("CAM-01", "INTERSECTION_DOWNTOWN"),
        ("CAM-02", "INTERSECTION_DOWNTOWN"),
        ("CAM-03", "INTERSECTION_DOWNTOWN")
    ]

    for cam_id, zone_id in cameras:
        t = threading.Thread(target=run_camera_worker, args=(cam_id, zone_id, publisher), daemon=True)
        threads.append(t)
        t.start()
        time.sleep(0.1)

    print("[*] All 3 camera vision pipelines running. Press Ctrl+C to stop.")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Stopping vision engine...")
        publisher.stop()

if __name__ == "__main__":
    main()
