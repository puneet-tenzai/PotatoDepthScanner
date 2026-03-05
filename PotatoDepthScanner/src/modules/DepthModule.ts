import { NativeModules, Platform } from 'react-native';

const { ArCoreDepthModule } = NativeModules;

export interface DepthResult {
  averageDistance: number;  // average depth in meters
  minDistance: number;      // closest point in meters
  maxDistance: number;      // farthest point in meters
  framesUsed: number;      // number of depth frames averaged
  totalPixels: number;     // total valid pixels sampled
}

/**
 * Check if ARCore depth is supported on this device
 */
export async function isDepthSupported(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  try {
    return await ArCoreDepthModule.checkDepthSupport();
  } catch {
    return false;
  }
}

/**
 * Perform a single-shot depth measurement.
 * Call this AFTER pausing the camera (isActive=false).
 * Opens ARCore, captures depth from multiple frames, returns averaged result.
 */
export async function measureDepth(): Promise<DepthResult> {
  if (Platform.OS !== 'android') {
    throw new Error('Depth measurement is only available on Android');
  }
  return await ArCoreDepthModule.measureDepth();
}
