"""
Enhanced Moving Edge Anomaly Detector (Sobel Delta Energy + Optical Flow + ROI Masking)
Computes:
1. Spatial Sobel gradients & temporal gradient delta energy.
2. Polygon ROI (Region of Interest) restricted zone filtering.
3. Directional Motion Vector (Farnebäck Optical Flow) for wrong-way and rapid deceleration detection.
4. Base64 snapshot generation with visual bounding annotations for instant incident replay.
"""

import cv2
import numpy as np
import time
import base64
from typing import Dict, Any, Tuple, Optional, List

class MovingEdgeDetector:
    def __init__(
        self,
        camera_id: str,
        zone_id: str = "INTERSECTION_DOWNTOWN",
        ksize: int = 3,
        edge_threshold: float = 35.0,
        spike_multiplier: float = 1.6,
        history_len: int = 30,
        roi_polygon: Optional[List[List[int]]] = None,
        expected_heading_deg: Optional[float] = None # e.g. 90 deg = Southbound
    ):
        self.camera_id = camera_id
        self.zone_id = zone_id
        self.ksize = ksize
        self.edge_threshold = edge_threshold
        self.spike_multiplier = spike_multiplier
        self.history_len = history_len
        self.expected_heading_deg = expected_heading_deg

        # Default ROI (central roadway/monitored area) if not specified
        self.roi_polygon = np.array(roi_polygon if roi_polygon else [[100, 50], [540, 50], [580, 430], [60, 430]], dtype=np.int32)

        self.prev_gray: Optional[np.ndarray] = None
        self.prev_sobel_mag: Optional[np.ndarray] = None
        self.baseline_history = []
        self.frame_count = 0
        self.last_event_time = 0.0
        self.cooldown_sec = 0.5

    def process_frame(self, frame: np.ndarray) -> Tuple[np.ndarray, Optional[Dict[str, Any]]]:
        self.frame_count += 1
        current_time = time.time()
        h, w = frame.shape[:2]

        # 1. Grayscale & Blur
        if len(frame.shape) == 3:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        else:
            gray = frame.copy()
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)

        # 2. ROI Mask
        mask = np.zeros((h, w), dtype=np.uint8)
        cv2.fillPoly(mask, [self.roi_polygon], 255)
        roi_pixel_count = max(1, np.count_nonzero(mask))

        # 3. Sobel Gradients
        gx = cv2.Sobel(blurred, cv2.CV_64F, 1, 0, ksize=self.ksize)
        gy = cv2.Sobel(blurred, cv2.CV_64F, 0, 1, ksize=self.ksize)
        sobel_mag = cv2.magnitude(gx, gy)
        sobel_masked = cv2.bitwise_and(sobel_mag, sobel_mag, mask=mask)

        # Binary edge density inside ROI
        edge_mask = (sobel_masked > self.edge_threshold).astype(np.uint8) * 255
        edge_density = float(np.count_nonzero(edge_mask) / roi_pixel_count)

        event_payload = None
        moving_energy = 0.0
        avg_motion_angle = 0.0
        avg_motion_speed = 0.0

        # 4. Inter-frame Differencing & Optical Flow
        if self.prev_sobel_mag is not None and self.prev_gray is not None:
            # Moving edge delta energy
            diff = cv2.absdiff(sobel_masked, self.prev_sobel_mag)
            moving_energy = float(np.sum(diff) / roi_pixel_count)

            # Adaptive baseline
            if len(self.baseline_history) >= self.history_len:
                self.baseline_history.pop(0)
            self.baseline_history.append(moving_energy)
            baseline_avg = float(np.mean(self.baseline_history)) if self.baseline_history else 1.0
            baseline_avg = max(baseline_avg, 0.05)
            ratio = moving_energy / baseline_avg

            # Directional Motion Estimation (Optical Flow on downsampled ROI)
            small_gray = cv2.resize(gray, (160, 120))
            small_prev = cv2.resize(self.prev_gray, (160, 120))
            flow = cv2.calcOpticalFlowFarneback(small_prev, small_gray, None, 0.5, 3, 15, 3, 5, 1.2, 0)
            
            fx, fy = flow[..., 0], flow[..., 1]
            mag, ang = cv2.cartToPolar(fx, fy, angleInDegrees=True)
            flow_mask = mag > 1.5
            if np.any(flow_mask):
                avg_motion_speed = float(np.mean(mag[flow_mask]))
                avg_motion_angle = float(np.mean(ang[flow_mask]))

            # Anomaly Classification
            if ratio >= self.spike_multiplier and (current_time - self.last_event_time) > self.cooldown_sec:
                self.last_event_time = current_time
                
                # Classify event semantics
                if self.expected_heading_deg is not None and abs(avg_motion_angle - self.expected_heading_deg) > 130:
                    event_type = "WRONG_WAY_MOVEMENT"
                elif ratio > 3.0:
                    event_type = "RESTRICTED_ZONE_INTRUSION"
                elif avg_motion_speed > 4.5:
                    event_type = "RAPID_SPEEDING_ANOMALY"
                else:
                    event_type = "MOVING_EDGE_SPIKE"

                # Generate Annotated Base64 Snapshot for Instant Dashboard Replay
                annotated_snapshot = frame.copy()
                cv2.polylines(annotated_snapshot, [self.roi_polygon], True, (0, 0, 255), 2)
                cv2.putText(annotated_snapshot, f"ALERT: {event_type}", (20, 35), 
                            cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
                cv2.putText(annotated_snapshot, f"Cam: {self.camera_id} | Ratio: {ratio:.1f}x | Heading: {avg_motion_angle:.0f} deg", 
                            (20, 65), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
                
                # Compress thumbnail to JPEG
                thumb = cv2.resize(annotated_snapshot, (320, 240))
                _, buf = cv2.imencode(".jpg", thumb, [int(cv2.IMWRITE_JPEG_QUALITY), 65])
                b64_thumb = f"data:image/jpeg;base64,{base64.b64encode(buf).decode('utf-8')}"

                event_payload = {
                    "cameraId": self.camera_id,
                    "zoneId": self.zone_id,
                    "eventType": event_type,
                    "edgeDensity": round(edge_density, 4),
                    "movingEnergy": round(moving_energy, 4),
                    "deltaRatio": round(ratio, 2),
                    "motionVectorAngle": round(avg_motion_angle, 1),
                    "velocityMagnitude": round(avg_motion_speed, 2),
                    "frameSeq": self.frame_count,
                    "detectedAt": int(current_time * 1000),
                    "snapshotBase64": b64_thumb
                }

        self.prev_gray = gray
        self.prev_sobel_mag = sobel_masked

        # Visual overlay
        vis = frame.copy()
        color = (0, 0, 255) if event_payload else (0, 255, 0)
        cv2.polylines(vis, [self.roi_polygon], True, color, 2)
        status_text = f"{self.camera_id} | Density: {edge_density:.2f}" if not event_payload else f"ANOMALY: {event_payload['eventType']}"
        cv2.putText(vis, status_text, (20, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

        return vis, event_payload
