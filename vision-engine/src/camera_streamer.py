"""
Camera Streamer: Supports Webcams, Video files (MP4/AVI), and Synthetic Frame Generation
"""

import cv2
import numpy as np
import time
from typing import Generator, Union

class CameraStreamer:
    def __init__(self, source: Union[int, str] = 0, loop: bool = True, fps: int = 30):
        self.source = source
        self.loop = loop
        self.fps = fps
        self.is_synthetic = (source == "synthetic")
        self.cap = None

        if not self.is_synthetic:
            self.cap = cv2.VideoCapture(source)
            if not self.cap.isOpened():
                print(f"[WARN] Failed to open source {source}. Falling back to synthetic stream.")
                self.is_synthetic = True

    def frames(self) -> Generator[np.ndarray, None, None]:
        frame_time = 1.0 / self.fps
        t = 0.0

        while True:
            start_loop = time.time()

            if self.is_synthetic:
                # Generate dynamic synthetic road & moving vehicle scene
                frame = np.zeros((480, 640, 3), dtype=np.uint8)
                # Draw road lanes
                cv2.rectangle(frame, (100, 0), (540, 480), (40, 40, 40), -1)
                cv2.line(frame, (320, 0), (320, 480), (255, 255, 255), 2)
                
                # Periodic simulated traffic/vehicles
                # Vehicle 1: Normal steady motion
                y1 = int((t * 120) % 520) - 40
                cv2.rectangle(frame, (180, y1), (240, y1 + 60), (0, 180, 255), -1)

                # Vehicle 2: Periodic rapid acceleration / anomaly every 8 seconds
                cycle = int(t) % 8
                if cycle >= 5:
                    speed = 300
                    y2 = int(((t - 5) * speed) % 520) - 40
                    cv2.rectangle(frame, (380, y2), (460, y2 + 80), (50, 50, 230), -1)
                    cv2.putText(frame, "SPEEDING ANOMALY", (350, max(20, y2)), 
                                cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 2)

                t += 0.033
                yield frame
            else:
                ret, frame = self.cap.read()
                if not ret:
                    if self.loop:
                        self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                        continue
                    else:
                        break
                yield frame

            elapsed = time.time() - start_loop
            sleep_time = max(0.001, frame_time - elapsed)
            time.sleep(sleep_time)

    def release(self):
        if self.cap:
            self.cap.release()
