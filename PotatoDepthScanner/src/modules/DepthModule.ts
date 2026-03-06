import { NativeModules, Platform } from 'react-native';

const { ArCoreDepthModule } = NativeModules;

export interface DepthResult {
  averageDistance: number;
  rawDistance: number;
  minDistance: number;
  maxDistance: number;
  framesUsed: number;
  totalPixels: number;
  method?: string;
  tooFar?: boolean;
  calibrationFactor?: number;
}

export async function isDepthSupported(): Promise<boolean> {
  if (Platform.OS !== 'android') return false;
  try {
    return await ArCoreDepthModule.checkDepthSupport();
  } catch {
    return false;
  }
}

export async function measureDepth(): Promise<DepthResult> {
  if (Platform.OS !== 'android') {
    throw new Error('Depth measurement is only available on Android');
  }
  return await ArCoreDepthModule.measureDepth();
}

/**
 * Diagnostic: list all cameras and their depth capabilities.
 * Useful for debugging which sensors are available.
 */
export async function diagnoseDepthSensors(): Promise<string> {
  if (Platform.OS !== 'android') return 'Not available on this platform';
  try {
    return await ArCoreDepthModule.diagnoseDepthSensors();
  } catch (e: any) {
    return `Error: ${e.message}`;
  }
}
