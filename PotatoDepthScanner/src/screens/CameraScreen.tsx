import React, { useState, useRef, useCallback, useEffect } from 'react';
import {
    View,
    StyleSheet,
    Alert,
    StatusBar,
    Text,
    TouchableOpacity,
    Image,
    Platform,
    Linking,
} from 'react-native';
import {
    Camera,
    useCameraDevice,
    useCameraPermission,
    PhotoFile,
} from 'react-native-vision-camera';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import { CaptureButton } from '../components/CaptureButton';
import { isDepthSupported, measureDepth, DepthResult } from '../modules/DepthModule';

export const CameraScreen: React.FC = () => {
    const cameraRef = useRef<Camera>(null);
    const device = useCameraDevice('back');
    const { hasPermission, requestPermission } = useCameraPermission();

    const [isCapturing, setIsCapturing] = useState(false);
    const [isMeasuring, setIsMeasuring] = useState(false);
    const [capturedPhoto, setCapturedPhoto] = useState<PhotoFile | null>(null);
    const [depthResult, setDepthResult] = useState<DepthResult | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [depthSupported, setDepthSupported] = useState<boolean | null>(null);

    // Check depth support on mount
    useEffect(() => {
        isDepthSupported().then(setDepthSupported);
    }, []);

    // Request camera permission on mount
    useEffect(() => {
        if (!hasPermission) {
            requestPermission();
        }
    }, [hasPermission, requestPermission]);

    // Capture photo then measure depth
    const handleCapture = useCallback(async () => {
        if (!cameraRef.current || isCapturing || isMeasuring) return;

        try {
            // Step 1: Take photo
            setIsCapturing(true);
            const photo = await cameraRef.current.takePhoto({
                flash: 'off',
                enableShutterSound: true,
            });
            setCapturedPhoto(photo);
            setIsCapturing(false);

            // Step 2: Measure depth with ToF sensor
            // (ToF is a separate camera device — no need to pause Vision Camera)
            setIsMeasuring(true);

            try {
                const result = await measureDepth();
                setDepthResult(result);
            } catch (e: any) {
                console.warn('Depth measurement failed:', e.message);
                setDepthResult(null);
            }

            setIsMeasuring(false);
        } catch (e: any) {
            Alert.alert('Capture Error', e.message || 'Failed to take photo');
            setIsCapturing(false);
            setIsMeasuring(false);
        }
    }, [isCapturing, isMeasuring]);

    // Save photo to gallery
    const handleSavePhoto = useCallback(async () => {
        if (!capturedPhoto) return;

        try {
            setIsSaving(true);
            const uri = `file://${capturedPhoto.path}`;
            await CameraRoll.saveAsset(uri, { type: 'photo' });
            Alert.alert('✅ Saved!', 'Photo saved to your gallery');
            setCapturedPhoto(null);
            setDepthResult(null);
        } catch (e: any) {
            Alert.alert('Save Error', e.message || 'Failed to save photo');
        } finally {
            setIsSaving(false);
        }
    }, [capturedPhoto]);

    // Discard captured photo
    const handleDiscard = useCallback(() => {
        setCapturedPhoto(null);
        setDepthResult(null);
    }, []);

    // --- Permission not granted ---
    if (!hasPermission) {
        return (
            <View style={styles.permissionContainer}>
                <StatusBar barStyle="light-content" backgroundColor="#0D0D0D" />
                <Text style={styles.permissionIcon}>📷</Text>
                <Text style={styles.permissionTitle}>Camera Access Required</Text>
                <Text style={styles.permissionText}>
                    This app needs camera access to capture photos and measure depth.
                </Text>
                <TouchableOpacity
                    style={styles.permissionButton}
                    onPress={requestPermission}>
                    <Text style={styles.permissionButtonText}>Grant Permission</Text>
                </TouchableOpacity>
                <TouchableOpacity
                    style={styles.settingsButton}
                    onPress={() => Linking.openSettings()}>
                    <Text style={styles.settingsButtonText}>Open Settings</Text>
                </TouchableOpacity>
            </View>
        );
    }

    // --- No camera device ---
    if (!device) {
        return (
            <View style={styles.permissionContainer}>
                <StatusBar barStyle="light-content" backgroundColor="#0D0D0D" />
                <Text style={styles.permissionIcon}>🚫</Text>
                <Text style={styles.permissionTitle}>No Camera Found</Text>
                <Text style={styles.permissionText}>
                    Could not find a back camera on this device.
                </Text>
            </View>
        );
    }

    // --- Photo preview with depth result ---
    if (capturedPhoto) {
        return (
            <View style={styles.previewContainer}>
                <StatusBar barStyle="light-content" backgroundColor="#0D0D0D" />

                <Image
                    source={{ uri: `file://${capturedPhoto.path}` }}
                    style={styles.previewImage}
                    resizeMode="contain"
                />

                {/* Depth measurement result */}
                {depthResult && (
                    <View style={styles.depthResultContainer}>
                        <View style={styles.depthResultCard}>
                            <Text style={styles.depthResultLabel}>DISTANCE TO GROUND</Text>
                            <Text style={styles.depthResultValue}>
                                {depthResult.averageDistance.toFixed(2)} m
                            </Text>
                            <Text style={styles.depthResultFeet}>
                                ≈ {(depthResult.averageDistance * 3.281).toFixed(1)} ft
                            </Text>
                            <View style={styles.depthDetails}>
                                <Text style={styles.depthDetailText}>
                                    Min: {depthResult.minDistance.toFixed(2)}m
                                </Text>
                                <Text style={styles.depthDetailText}>
                                    Max: {depthResult.maxDistance.toFixed(2)}m
                                </Text>
                            </View>
                            <Text style={styles.depthFrameInfo}>
                                {depthResult.framesUsed} frames averaged
                            </Text>
                        </View>
                    </View>
                )}

                {!depthResult && !isMeasuring && (
                    <View style={styles.depthResultContainer}>
                        <View style={[styles.depthResultCard, styles.depthResultCardError]}>
                            <Text style={styles.depthResultLabel}>DEPTH MEASUREMENT</Text>
                            <Text style={styles.depthUnavailable}>Not available</Text>
                            <Text style={styles.depthDetailText}>
                                Could not measure depth for this photo
                            </Text>
                        </View>
                    </View>
                )}

                {isMeasuring && (
                    <View style={styles.depthResultContainer}>
                        <View style={styles.depthResultCard}>
                            <Text style={styles.depthResultLabel}>MEASURING DEPTH...</Text>
                            <Text style={styles.depthMeasuring}>⏳</Text>
                            <Text style={styles.depthDetailText}>
                                Processing with ARCore
                            </Text>
                        </View>
                    </View>
                )}

                <View style={styles.previewActions}>
                    <TouchableOpacity
                        style={styles.discardButton}
                        onPress={handleDiscard}>
                        <Text style={styles.discardText}>✕ Discard</Text>
                    </TouchableOpacity>

                    <TouchableOpacity
                        style={[styles.saveButton, isMeasuring && styles.saveButtonDisabled]}
                        onPress={handleSavePhoto}
                        disabled={isSaving || isMeasuring}>
                        <Text style={styles.saveText}>
                            {isSaving ? 'Saving...' : '💾 Save'}
                        </Text>
                    </TouchableOpacity>
                </View>
            </View>
        );
    }

    // --- Main camera view ---
    return (
        <View style={styles.container}>
            <StatusBar barStyle="light-content" backgroundColor="transparent" translucent />

            <Camera
                ref={cameraRef}
                style={StyleSheet.absoluteFill}
                device={device}
                isActive={true}
                photo={true}
            />

            {/* Top bar */}
            <View style={styles.topBar}>
                <Text style={styles.appTitle}>🥔 Potato Depth Scanner</Text>
                {depthSupported === true && (
                    <Text style={styles.depthBadge}>ARCore ✓</Text>
                )}
            </View>

            {/* Capture button */}
            <CaptureButton
                onCapture={handleCapture}
                isCapturing={isCapturing}
                isMeasuring={isMeasuring}
            />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#000000',
    },
    permissionContainer: {
        flex: 1,
        backgroundColor: '#0D0D0D',
        justifyContent: 'center',
        alignItems: 'center',
        padding: 30,
    },
    permissionIcon: {
        fontSize: 64,
        marginBottom: 20,
    },
    permissionTitle: {
        color: '#FFFFFF',
        fontSize: 24,
        fontWeight: '800',
        marginBottom: 12,
        textAlign: 'center',
    },
    permissionText: {
        color: '#AAAAAA',
        fontSize: 16,
        textAlign: 'center',
        lineHeight: 24,
        marginBottom: 30,
    },
    permissionButton: {
        backgroundColor: '#6C63FF',
        paddingVertical: 14,
        paddingHorizontal: 40,
        borderRadius: 30,
        marginBottom: 16,
    },
    permissionButtonText: {
        color: '#FFFFFF',
        fontSize: 16,
        fontWeight: '700',
    },
    settingsButton: {
        paddingVertical: 10,
        paddingHorizontal: 30,
    },
    settingsButtonText: {
        color: '#6C63FF',
        fontSize: 14,
        fontWeight: '600',
    },
    topBar: {
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        paddingTop: Platform.OS === 'android' ? 40 : 50,
        paddingBottom: 12,
        paddingHorizontal: 20,
        backgroundColor: 'rgba(0, 0, 0, 0.4)',
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
    },
    appTitle: {
        color: '#FFFFFF',
        fontSize: 18,
        fontWeight: '800',
        letterSpacing: 0.5,
    },
    depthBadge: {
        color: '#00E676',
        fontSize: 12,
        fontWeight: '700',
        backgroundColor: 'rgba(0, 230, 118, 0.15)',
        paddingVertical: 4,
        paddingHorizontal: 10,
        borderRadius: 10,
    },
    previewContainer: {
        flex: 1,
        backgroundColor: '#0D0D0D',
    },
    previewImage: {
        flex: 1,
    },
    depthResultContainer: {
        position: 'absolute',
        top: 60,
        left: 20,
        right: 20,
        alignItems: 'center',
    },
    depthResultCard: {
        backgroundColor: 'rgba(0, 0, 0, 0.85)',
        borderRadius: 16,
        paddingVertical: 16,
        paddingHorizontal: 24,
        alignItems: 'center',
        borderWidth: 1,
        borderColor: '#00E676',
        minWidth: 200,
    },
    depthResultCardError: {
        borderColor: '#FF9100',
    },
    depthResultLabel: {
        color: '#AAAAAA',
        fontSize: 11,
        fontWeight: '700',
        letterSpacing: 2,
        marginBottom: 8,
    },
    depthResultValue: {
        color: '#00E676',
        fontSize: 36,
        fontWeight: '800',
    },
    depthResultFeet: {
        color: '#888888',
        fontSize: 14,
        fontWeight: '500',
        marginTop: 4,
    },
    depthDetails: {
        flexDirection: 'row',
        gap: 16,
        marginTop: 12,
    },
    depthDetailText: {
        color: '#888888',
        fontSize: 12,
        fontWeight: '500',
    },
    depthFrameInfo: {
        color: '#666666',
        fontSize: 10,
        fontWeight: '500',
        marginTop: 8,
    },
    depthUnavailable: {
        color: '#FF9100',
        fontSize: 20,
        fontWeight: '700',
    },
    depthMeasuring: {
        fontSize: 32,
        marginVertical: 8,
    },
    previewActions: {
        flexDirection: 'row',
        justifyContent: 'space-around',
        paddingVertical: 24,
        paddingHorizontal: 20,
        backgroundColor: '#0D0D0D',
    },
    discardButton: {
        backgroundColor: 'rgba(255, 23, 68, 0.2)',
        borderWidth: 1,
        borderColor: '#FF1744',
        paddingVertical: 14,
        paddingHorizontal: 30,
        borderRadius: 30,
    },
    discardText: {
        color: '#FF1744',
        fontSize: 16,
        fontWeight: '700',
    },
    saveButton: {
        backgroundColor: '#6C63FF',
        paddingVertical: 14,
        paddingHorizontal: 30,
        borderRadius: 30,
    },
    saveButtonDisabled: {
        opacity: 0.5,
    },
    saveText: {
        color: '#FFFFFF',
        fontSize: 16,
        fontWeight: '700',
    },
});
