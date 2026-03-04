import {
  NativeModules,
  NativeEventEmitter,
  Platform,
} from 'react-native';
import { useState, useEffect, useCallback } from 'react';

const { ArCoreDepthModule } = NativeModules;

export interface DepthData {
  distance: number;
  rawDistance: number;
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
 * React hook for depth data from the native ArCoreDepthView.
 * The view handles session management — this hook just listens for events.
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

    const depthSub = eventEmitter.addListener('onDepthData', (data: DepthData) => {
      setDepthData(data);
      setError(null); // Clear any previous error on successful data
    });

    const errorSub = eventEmitter.addListener('onDepthError', (data: { error: string }) => {
      setError(data.error);
    });

    return () => {
      depthSub.remove();
      errorSub.remove();
    };
  }, [isActive]);

  const toggleDepth = useCallback(() => {
    setError(null);
    if (isActive) {
      setIsActive(false);
      setDepthData(null);
    } else {
      setIsActive(true);
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
