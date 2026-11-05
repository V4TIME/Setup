package com.example.setup;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class FlashlightTileService extends TileService {

    private CameraManager cameraManager;
    private String cameraId;
    private boolean torchOn = false;
    private boolean callbackRegistered = false;

    private final CameraManager.TorchCallback torchCallback =
            new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(cameraId)) {
                torchOn = enabled;
                updateTile();
            }
        }

        @Override
        public void onTorchModeUnavailable(String id) {
            if (id.equals(cameraId)) {
                torchOn = false;
                updateTile();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        findCamera();
    }

    private void findCamera() {
        if (cameraManager == null) return;

        // No-permission path: getCameraIdList() does NOT require CAMERA permission.
        // We deliberately do NOT query FLASH_INFO_AVAILABLE here, because on
        // Android 10+ that key needs CAMERA permission and returns null when
        // the app does not hold it - which would wrongly mark the tile as
        // UNAVAILABLE even though setTorchMode itself needs no permission at all.
        // Strategy: remember the first camera id, then let setTorchMode's own
        // CameraAccessException tell us whether the flash exists / is busy.
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids != null && ids.length > 0) {
                // Prefer the back camera (flash is usually on the back unit).
                for (String id : ids) {
                    try {
                        CameraCharacteristics characteristics =
                                cameraManager.getCameraCharacteristics(id);
                        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                            cameraId = id;
                            return;
                        }
                    } catch (CameraAccessException | SecurityException ignored) {
                        // Fallback to any id below.
                    }
                }
                cameraId = ids[0];
            }
        } catch (CameraAccessException | SecurityException e) {
            cameraId = null;
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        findCamera();

        if (cameraManager != null && !callbackRegistered) {
            cameraManager.registerTorchCallback(
                    torchCallback,
                    new Handler(Looper.getMainLooper())
            );
            callbackRegistered = true;
        }

        updateTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();

        if (cameraManager != null && callbackRegistered) {
            cameraManager.unregisterTorchCallback(torchCallback);
            callbackRegistered = false;
        }
    }

    @Override
    public void onClick() {
        super.onClick();

        findCamera();

        if (cameraId == null) {
            Toast.makeText(
                    this,
                    "No camera with flash found",
                    Toast.LENGTH_SHORT
            ).show();
            updateTile();
            return;
        }

        try {
            cameraManager.setTorchMode(cameraId, !torchOn);
        } catch (CameraAccessException | SecurityException e) {
            Toast.makeText(
                    this,
                    "Could not switch on the flashlight",
                    Toast.LENGTH_SHORT
            ).show();
        }

        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (cameraId == null) {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel("Flashlight");
        } else {
            tile.setState(torchOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("Flashlight");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(torchOn ? "On" : "Off");
            }
        }

        tile.updateTile();
    }
}
