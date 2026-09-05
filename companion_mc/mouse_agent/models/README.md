# Hand Landmarker model (MediaPipe Tasks)

Download `hand_landmarker.task` and place it here:

```powershell
mkdir models
# From MediaPipe model zoo, or copy from glasses APK assets:
#   adb pull /data/app/.../base.apk  (or extract from mouse_controller build)
#   unzip and copy assets/ml/hand_landmarker.task → models/hand_landmarker.task
```

Direct download (Google storage):

```powershell
curl -L -o models/hand_landmarker.task ^
  "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
```

The agent looks for `models/hand_landmarker.task` by default (`--model` to override).
