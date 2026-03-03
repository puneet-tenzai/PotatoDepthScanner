import React from 'react';
import { TouchableOpacity, View, StyleSheet, Text } from 'react-native';

interface CaptureButtonProps {
    onCapture: () => void;
    onToggleDepth: () => void;
    isDepthActive: boolean;
    isDepthSupported: boolean | null;
    isCapturing: boolean;
}

export const CaptureButton: React.FC<CaptureButtonProps> = ({
    onCapture,
    onToggleDepth,
    isDepthActive,
    isDepthSupported,
    isCapturing,
}) => {
    return (
        <View style={styles.container}>
            {/* Depth toggle button */}
            {isDepthSupported !== false && (
                <TouchableOpacity
                    style={[
                        styles.depthButton,
                        isDepthActive && styles.depthButtonActive,
                    ]}
                    onPress={onToggleDepth}
                    activeOpacity={0.7}>
                    <Text style={styles.depthIcon}>📏</Text>
                    <Text
                        style={[
                            styles.depthText,
                            isDepthActive && styles.depthTextActive,
                        ]}>
                        {isDepthActive ? 'DEPTH ON' : 'DEPTH'}
                    </Text>
                </TouchableOpacity>
            )}

            {/* Main capture button */}
            <TouchableOpacity
                style={styles.captureOuter}
                onPress={onCapture}
                disabled={isCapturing}
                activeOpacity={0.6}>
                <View
                    style={[
                        styles.captureInner,
                        isCapturing && styles.captureInnerActive,
                    ]}>
                    {isCapturing && <Text style={styles.captureText}>📸</Text>}
                </View>
            </TouchableOpacity>

            {/* Spacer for symmetry */}
            <View style={styles.spacer} />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        bottom: 30,
        left: 0,
        right: 0,
        flexDirection: 'row',
        justifyContent: 'space-around',
        alignItems: 'center',
        paddingHorizontal: 30,
    },
    captureOuter: {
        width: 80,
        height: 80,
        borderRadius: 40,
        borderWidth: 4,
        borderColor: '#FFFFFF',
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: 'rgba(255, 255, 255, 0.15)',
    },
    captureInner: {
        width: 64,
        height: 64,
        borderRadius: 32,
        backgroundColor: '#FFFFFF',
        justifyContent: 'center',
        alignItems: 'center',
    },
    captureInnerActive: {
        backgroundColor: '#FF1744',
    },
    captureText: {
        fontSize: 24,
    },
    depthButton: {
        width: 60,
        height: 60,
        borderRadius: 30,
        backgroundColor: 'rgba(255, 255, 255, 0.15)',
        borderWidth: 2,
        borderColor: 'rgba(255, 255, 255, 0.4)',
        justifyContent: 'center',
        alignItems: 'center',
    },
    depthButtonActive: {
        backgroundColor: 'rgba(0, 230, 118, 0.3)',
        borderColor: '#00E676',
    },
    depthIcon: {
        fontSize: 20,
    },
    depthText: {
        color: '#FFFFFF',
        fontSize: 8,
        fontWeight: '800',
        letterSpacing: 0.5,
        marginTop: 2,
    },
    depthTextActive: {
        color: '#00E676',
    },
    spacer: {
        width: 60,
        height: 60,
    },
});
