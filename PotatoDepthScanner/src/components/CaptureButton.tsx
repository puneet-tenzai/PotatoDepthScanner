import React from 'react';
import { TouchableOpacity, View, StyleSheet, Text, ActivityIndicator } from 'react-native';

interface CaptureButtonProps {
    onCapture: () => void;
    isCapturing: boolean;
    isMeasuring: boolean;
}

export const CaptureButton: React.FC<CaptureButtonProps> = ({
    onCapture,
    isCapturing,
    isMeasuring,
}) => {
    const isDisabled = isCapturing || isMeasuring;

    return (
        <View style={styles.container}>
            {/* Status indicator */}
            {isMeasuring && (
                <View style={styles.statusContainer}>
                    <ActivityIndicator size="small" color="#00E676" />
                    <Text style={styles.statusText}>Measuring depth...</Text>
                </View>
            )}

            {/* Main capture button */}
            <TouchableOpacity
                style={[styles.captureOuter, isDisabled && styles.captureDisabled]}
                onPress={onCapture}
                disabled={isDisabled}
                activeOpacity={0.6}>
                <View
                    style={[
                        styles.captureInner,
                        isCapturing && styles.captureInnerActive,
                    ]}>
                    {isCapturing ? (
                        <Text style={styles.captureText}>📸</Text>
                    ) : (
                        <Text style={styles.captureHint}>TAP</Text>
                    )}
                </View>
            </TouchableOpacity>

            {/* Info text */}
            <Text style={styles.infoText}>
                Capture + Measure Depth
            </Text>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        bottom: 30,
        left: 0,
        right: 0,
        alignItems: 'center',
    },
    statusContainer: {
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: 'rgba(0, 0, 0, 0.7)',
        paddingVertical: 8,
        paddingHorizontal: 16,
        borderRadius: 20,
        marginBottom: 16,
    },
    statusText: {
        color: '#00E676',
        fontSize: 13,
        fontWeight: '600',
        marginLeft: 8,
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
    captureDisabled: {
        opacity: 0.5,
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
    captureHint: {
        fontSize: 12,
        fontWeight: '800',
        color: '#333',
        letterSpacing: 1,
    },
    infoText: {
        color: 'rgba(255, 255, 255, 0.6)',
        fontSize: 11,
        fontWeight: '500',
        marginTop: 8,
    },
});
