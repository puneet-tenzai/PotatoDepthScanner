import React, { useEffect, useRef } from 'react';
import {
    requireNativeComponent,
    UIManager,
    findNodeHandle,
    Platform,
    ViewStyle,
    StyleProp,
} from 'react-native';

interface ArCoreDepthViewProps {
    style?: StyleProp<ViewStyle>;
    isActive: boolean;
}

const NativeArCoreDepthView =
    Platform.OS === 'android'
        ? requireNativeComponent<any>('ArCoreDepthView')
        : null;

export const ArCoreDepthViewComponent: React.FC<ArCoreDepthViewProps> = ({
    style,
    isActive,
}) => {
    const viewRef = useRef<any>(null);

    useEffect(() => {
        if (Platform.OS !== 'android' || !viewRef.current) return;

        const handle = findNodeHandle(viewRef.current);
        if (!handle) return;

        if (isActive) {
            UIManager.dispatchViewManagerCommand(handle, 'resume', []);
        } else {
            UIManager.dispatchViewManagerCommand(handle, 'pause', []);
        }
    }, [isActive]);

    if (Platform.OS !== 'android' || !NativeArCoreDepthView) {
        return null;
    }

    return <NativeArCoreDepthView ref={viewRef} style={style} />;
};
