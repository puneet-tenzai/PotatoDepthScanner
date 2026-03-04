import {
  NativeModules,
  NativeEventEmitter,
  Platform,
} from 'react-native';
import { useState, useEffect, useCallback, useRef } from 'react';

const { ArCoreDepthModule } = NativeModules;

export interface DepthData {
  distance: number;
  depthWidth: number;
  depthHeight: number;
  confidence: number;
}

/**
 * Check if ARCore Depth is supported on this device
 */
export async function isDepthSupported(): Promise<boolean> {
  if (Platform.OS !== 'android') {
    return false;
  }
  try {
    return await ArCoreDepthModule.checkDepthSupport();
  } catch {
    return false;
  }
}

/**
 * Start the ARCore depth session
 */
export async function startDepthSession(): Promise<boolean> {
  if (Platform.OS !== 'android') {
    throw new Error('Depth session is only supported on Android');
  }
  return await ArCoreDepthModule.startDepthSession();
}

/**
 * Stop the ARCore depth session
 */
export async function stopDepthSession(): Promise<boolean> {
  if (Platform.OS !== 'android') {
    return true;
  }
  return await ArCoreDepthModule.stopDepthSession();
}

/**
 * Delay helper
 */
function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * React hook for subscribing to depth data.
 *
 * Accepts callbacks to pause/resume the Vision Camera, since ARCore and
 * Vision Camera cannot use the back camera simultaneously.
 *
 * @param onCameraPause  Called BEFORE ARCore starts (release the camera)
 * @param onCameraResume Called AFTER ARCore stops (re-acquire the camera)
 */
export function useDepthData(
  onCameraPause?: () => void,
  onCameraResume?: () => void,
) {
  const [depthData, setDepthData] = useState<DepthData | null>(null);
  const [isActive, setIsActive] = useState(false);
  const [isSupported, setIsSupported] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const isTogglingRef = useRef(false);

  // Check support on mount
  useEffect(() => {
    isDepthSupported().then(setIsSupported);
  }, []);

  // Subscribe to depth events when active
  useEffect(() => {
    if (!isActive || Platform.OS !== 'android') {
      return;
    }

    const eventEmitter = new NativeEventEmitter(ArCoreDepthModule);

    const depthSub = eventEmitter.addListener('onDepthData', (data: DepthData) => {
      setDepthData(data);
    });

    const errorSub = eventEmitter.addListener('onDepthError', (data: { error: string }) => {
      setError(data.error);
    });

    return () => {
      depthSub.remove();
      errorSub.remove();
    };
  }, [isActive]);

  const toggleDepth = useCallback(async () => {
    // Prevent double-toggling
    if (isTogglingRef.current) return;
    isTogglingRef.current = true;

    try {
      setError(null);
      if (isActive) {
        // Stop ARCore first, then resume camera
        await stopDepthSession();
        setIsActive(false);
        setDepthData(null);

        // Small delay before resuming camera
        await delay(200);
        onCameraResume?.();
      } else {
        // Pause camera first, then start ARCore
        onCameraPause?.();

        // Wait for camera hardware to be fully released
        // Vision Camera needs time to close the camera device
        await delay(1500);

        await startDepthSession();
        setIsActive(true);
      }
    } catch (e: any) {
      setError(e.message || 'Failed to toggle depth');
      setIsActive(false);
      // Resume camera if ARCore failed to start
      onCameraResume?.();
    } finally {
      isTogglingRef.current = false;
    }
  }, [isActive, onCameraPause, onCameraResume]);

  return {
    depthData,
    isActive,
    isSupported,
    error,
    toggleDepth,
  };
}
