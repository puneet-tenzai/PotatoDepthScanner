import React from 'react';
import { View, Text, StyleSheet, Animated } from 'react-native';
import type { DepthData } from '../modules/DepthModule';

interface DepthOverlayProps {
    depthData: DepthData | null;
    isActive: boolean;
    isSupported: boolean | null;
    error: string | null;
}

const getDistanceColor = (distance: number): string => {
    if (distance <= 0) return '#888888';
    if (distance < 0.5) return '#00E676'; // Bright green - very close
    if (distance < 1.0) return '#76FF03'; // Light green
    if (distance < 2.0) return '#FFEA00'; // Yellow
    if (distance < 3.0) return '#FF9100'; // Orange
    return '#FF1744'; // Red - far
};

const getDistanceLabel = (distance: number): string => {
    if (distance <= 0) return 'Measuring...';
    if (distance < 0.3) return 'Very Close';
    if (distance < 1.0) return 'Close';
    if (distance < 2.0) return 'Medium';
    if (distance < 3.0) return 'Far';
    return 'Very Far';
};

export const DepthOverlay: React.FC<DepthOverlayProps> = ({
    depthData,
    isActive,
    isSupported,
    error,
}) => {
    if (!isActive) return null;

    const distance = depthData?.distance ?? 0;
    const color = getDistanceColor(distance);

    return (
        <View style={styles.container} pointerEvents="none">
            {/* Status bar */}
            {error && (
                <View style={styles.errorBanner}>
                    <Text style={styles.errorText}>⚠️ {error}</Text>
                </View>
            )}

            {isSupported === false && (
                <View style={styles.errorBanner}>
                    <Text style={styles.errorText}>
                        📱 Depth not supported on this device
                    </Text>
                </View>
            )}

            {/* Center crosshair */}
            <View style={styles.crosshairContainer}>
                <View style={[styles.crosshairCircle, { borderColor: color }]}>
                    <View style={[styles.crosshairDot, { backgroundColor: color }]} />
                </View>

                {/* Distance info */}
                <View style={[styles.distanceCard, { borderColor: color }]}>
                    <Text style={styles.distanceLabel}>DISTANCE</Text>
                    <Text style={[styles.distanceValue, { color }]}>
                        {distance > 0 ? `${distance.toFixed(2)} m` : '-- m'}
                    </Text>
                    <Text style={[styles.distanceCategory, { color }]}>
                        {getDistanceLabel(distance)}
                    </Text>
                </View>
            </View>

            {/* Depth active indicator */}
            <View style={styles.activeIndicator}>
                <View style={[styles.activeDot, { backgroundColor: '#00E676' }]} />
                <Text style={styles.activeText}>DEPTH ACTIVE</Text>
            </View>
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        ...StyleSheet.absoluteFillObject,
        justifyContent: 'center',
        alignItems: 'center',
    },
    errorBanner: {
        position: 'absolute',
        top: 60,
        left: 20,
        right: 20,
        backgroundColor: 'rgba(255, 23, 68, 0.85)',
        paddingVertical: 10,
        paddingHorizontal: 16,
        borderRadius: 12,
        alignItems: 'center',
    },
    errorText: {
        color: '#FFFFFF',
        fontSize: 14,
        fontWeight: '600',
    },
    crosshairContainer: {
        alignItems: 'center',
    },
    crosshairCircle: {
        width: 60,
        height: 60,
        borderRadius: 30,
        borderWidth: 2,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: 'rgba(0, 0, 0, 0.2)',
    },
    crosshairDot: {
        width: 10,
        height: 10,
        borderRadius: 5,
    },
    distanceCard: {
        marginTop: 16,
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        paddingVertical: 14,
        paddingHorizontal: 24,
        borderRadius: 16,
        borderWidth: 1,
        alignItems: 'center',
        minWidth: 160,
    },
    distanceLabel: {
        color: '#AAAAAA',
        fontSize: 11,
        fontWeight: '700',
        letterSpacing: 2,
        marginBottom: 4,
    },
    distanceValue: {
        fontSize: 36,
        fontWeight: '800',
        letterSpacing: -1,
    },
    distanceCategory: {
        fontSize: 13,
        fontWeight: '600',
        marginTop: 2,
        textTransform: 'uppercase',
        letterSpacing: 1,
    },
    activeIndicator: {
        position: 'absolute',
        bottom: 120,
        flexDirection: 'row',
        alignItems: 'center',
        backgroundColor: 'rgba(0, 0, 0, 0.6)',
        paddingVertical: 6,
        paddingHorizontal: 14,
        borderRadius: 20,
    },
    activeDot: {
        width: 8,
        height: 8,
        borderRadius: 4,
        marginRight: 8,
    },
    activeText: {
        color: '#00E676',
        fontSize: 11,
        fontWeight: '700',
        letterSpacing: 1,
    },
});
