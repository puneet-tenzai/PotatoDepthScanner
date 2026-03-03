import {
  NativeModules,
  NativeEventEmitter,
  Platform,
} from 'react-native';
import {useState, useEffect, useCallback} from 'react';

const {ArCoreDepthModule} = NativeModules;

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
 * React hook for subscribing to depth data
 */
export function useDepthData() {
  const [depthData, setDepthData] = useState<DepthData | null>(null);
  const [isActive, setIsActive] = useState(false);
  const [isSupported, setIsSupported] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);

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
    const subscription = eventEmitter.addListener('onDepthData', (data: DepthData) => {
      setDepthData(data);
    });

    return () => {
      subscription.remove();
    };
  }, [isActive]);

  const toggleDepth = useCallback(async () => {
    try {
      setError(null);
      if (isActive) {
        await stopDepthSession();
        setIsActive(false);
        setDepthData(null);
      } else {
        await startDepthSession();
        setIsActive(true);
      }
    } catch (e: any) {
      setError(e.message || 'Failed to toggle depth');
      setIsActive(false);
    }
  }, [isActive]);

  return {
    depthData,
    isActive,
    isSupported,
    error,
    toggleDepth,
  };
}
