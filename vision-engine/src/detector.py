"""
Moving Edge Anomaly Detector (Sobel Delta Energy)
Computes spatial Sobel gradients and temporal gradient energy diffs.
"""

import cv2
import numpy as np
import time
from typing import Dict, Any, Tuple, Optional

class MovingEdgeDetector:
    def __init__(
        self,
        camera_id: str,
        zone_id: str = "INTERSECTION_01",
        ksize: int = 3,
        edge_threshold: float = 40.0,
        spike_multiplier: float = 1.8,
        history_len: int = 30
    ):
        self.camera_id = camera_id
        self.zone_id = zone_id
        self.ksize = ksize
        self.edge_threshold = edge_threshold
        self.spike_multiplier = spike_multiplier
        self.history_len = history_len

        self.prev_sobel_mag: Optional[np.ndarray] = None
        self.baseline_history = []
        self.frame_count = 0
        self.last_event_time = 0.0
        self.cooldown_sec = 0.6  # debounce rapid duplicate firings

    def process_frame(self, frame: np.ndarray) -> Tuple[np.ndarray, Optional[Dict[str, Any]]]:
        """
        Processes a raw BGR frame:
        1. Grayscale & Gaussian blur
        2. Sobel horizontal (Gx) and vertical (Gy) gradients
        3. Gradient Magnitude G = sqrt(Gx^2 + Gy^2)
        4. Inter-frame delta energy ||G_t - G_{t-1}||
        5. Spatio-temporal anomaly trigger
        """
        self.frame_count += 1
        current_time = time.time()
        
        # 1. Preprocessing
        if len(frame.shape) == 3:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        else:
            gray = frame
        blurred = cv2.GaussianBlur(gray, (5, 5), 0)

        # 2. Sobel Gradients
        gx = cv2.Sobel(blurred, cv2.CV_64F, 1, 0, ksize=self.ksize)
        gy = cv2.Sobel(blurred, cv2.CV_64F, 0, 1, ksize=self.ksize)
        sobel_mag = cv2.magnitude(gx, gy)

        # Binary edge mask
        edge_mask = (sobel_mag > self.edge_threshold).astype(np.uint8) * 255
        edge_density = float(np.sum(edge_mask > 0) / (edge_mask.shape[0] * edge_mask.shape[1]))

        event_payload = None
        moving_energy = 0.0

        # 3. Inter-frame Moving Edge Difference
        if self.prev_sobel_mag is not None:
            diff = cv2.absdiff(sobel_mag, self.prev_sobel_mag)
            moving_energy = float(np.mean(diff))

            # Maintain adaptive baseline
            if len(self.baseline_history) >= self.history_len:
                self.baseline_history.pop(0)
            self.baseline_history.append(moving_energy)

            baseline_avg = float(np.mean(self.baseline_history)) if self.baseline_history else 1.0
            baseline_avg = max(baseline_avg, 0.05)  # avoid division by zero

            ratio = moving_energy / baseline_avg

            # 4. Anomaly Condition
            if ratio >= self.spike_multiplier and (current_time - self.last_event_time) > self.cooldown_sec:
                self.last_event_time = current_time
                event_type = "RAPID_MOTION_SPIKE" if ratio > 2.5 else "MOVING_EDGE_ANOMALY"
                
                event_payload = {
                    "cameraId": self.camera_id,
                    "zoneId": self.zone_id,
                    "eventType": event_type,
                    "edgeDensity": round(edge_density, 4),
                    "movingEnergy": round(moving_energy, 4),
                    "deltaRatio": round(ratio, 2),
                    "frameSeq": self.frame_count,
                    "detectedAt": int(current_time * 1000)
                }

        self.prev_sobel_mag = sobel_mag

        # Return visualization overlay & event (if any)
        vis = cv2.cvtColor(edge_mask, cv2.COLOR_GRAY2BGR)
        if event_payload:
            cv2.putText(vis, f"ALERT: {event_payload['eventType']} ({event_payload['deltaRatio']}x)", 
                        (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
        else:
            cv2.putText(vis, f"{self.camera_id} | Density: {edge_density:.3f}", 
                        (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 1)

        return vis, event_payload
