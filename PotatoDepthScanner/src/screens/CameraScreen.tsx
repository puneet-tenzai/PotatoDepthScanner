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
import { DepthOverlay } from '../components/DepthOverlay';
import { CaptureButton } from '../components/CaptureButton';
import { useDepthData } from '../modules/DepthModule';

export const CameraScreen: React.FC = () => {
    const cameraRef = useRef<Camera>(null);
    const device = useCameraDevice('back');
    const { hasPermission, requestPermission } = useCameraPermission();

    const [isCapturing, setIsCapturing] = useState(false);
    const [capturedPhoto, setCapturedPhoto] = useState<PhotoFile | null>(null);
    const [isSaving, setIsSaving] = useState(false);
    const [isCameraActive, setIsCameraActive] = useState(true);

    // Camera pause/resume callbacks for ARCore
    const handleCameraPause = useCallback(() => {
        setIsCameraActive(false);
    }, []);

    const handleCameraResume = useCallback(() => {
        setIsCameraActive(true);
    }, []);

    const { depthData, isActive: isDepthActive, isSupported, error, toggleDepth } =
        useDepthData(handleCameraPause, handleCameraResume);

    // Request camera permission on mount
    useEffect(() => {
        if (!hasPermission) {
            requestPermission();
        }
    }, [hasPermission, requestPermission]);

    // Capture photo
    const handleCapture = useCallback(async () => {
        if (!cameraRef.current || isCapturing) return;

        // Can't capture while depth is active (camera is paused)
        if (isDepthActive) {
            Alert.alert(
                'Depth Active',
                'Please turn off depth sensing before taking a photo.',
                [{ text: 'OK' }],
            );
            return;
        }

        try {
            setIsCapturing(true);
            const photo = await cameraRef.current.takePhoto({
                flash: 'off',
                enableShutterSound: true,
            });
            setCapturedPhoto(photo);
        } catch (e: any) {
            Alert.alert('Capture Error', e.message || 'Failed to take photo');
        } finally {
            setIsCapturing(false);
        }
    }, [isCapturing, isDepthActive]);

    // Save photo to gallery
    const handleSavePhoto = useCallback(async () => {
        if (!capturedPhoto) return;

        try {
            setIsSaving(true);
            const uri = `file://${capturedPhoto.path}`;
            await CameraRoll.saveAsset(uri, { type: 'photo' });
            Alert.alert('✅ Saved!', 'Photo saved to your gallery');
            setCapturedPhoto(null);
        } catch (e: any) {
            Alert.alert('Save Error', e.message || 'Failed to save photo');
        } finally {
            setIsSaving(false);
        }
    }, [capturedPhoto]);

    // Discard captured photo
    const handleDiscard = useCallback(() => {
        setCapturedPhoto(null);
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

    // --- Photo preview ---
    if (capturedPhoto) {
        return (
            <View style={styles.previewContainer}>
                <StatusBar barStyle="light-content" backgroundColor="#0D0D0D" />
                <Image
                    source={{ uri: `file://${capturedPhoto.path}` }}
                    style={styles.previewImage}
                    resizeMode="contain"
                />

                {/* Depth info at time of capture */}
                {depthData && depthData.distance > 0 && (
                    <View style={styles.previewDepthBadge}>
                        <Text style={styles.previewDepthText}>
                            📏 {depthData.distance.toFixed(2)} m
                        </Text>
                    </View>
                )}

                <View style={styles.previewActions}>
                    <TouchableOpacity
                        style={styles.discardButton}
                        onPress={handleDiscard}>
                        <Text style={styles.discardText}>✕ Discard</Text>
                    </TouchableOpacity>

                    <TouchableOpacity
                        style={styles.saveButton}
                        onPress={handleSavePhoto}
                        disabled={isSaving}>
                        <Text style={styles.saveText}>
                            {isSaving ? 'Saving...' : '💾 Save to Gallery'}
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
                isActive={isCameraActive}
                photo={true}
            />

            {/* Depth overlay */}
            <DepthOverlay
                depthData={depthData}
                isActive={isDepthActive}
                isSupported={isSupported}
                error={error}
            />

            {/* Top bar with app name */}
            <View style={styles.topBar}>
                <Text style={styles.appTitle}>🥔 Potato Depth Scanner</Text>
            </View>

            {/* Capture and depth controls */}
            <CaptureButton
                onCapture={handleCapture}
                onToggleDepth={toggleDepth}
                isDepthActive={isDepthActive}
                isDepthSupported={isSupported}
                isCapturing={isCapturing}
            />
        </View>
    );
};

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#000000',
    },
    // Permission screen
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
    // Top bar
    topBar: {
        position: 'absolute',
        top: 0,
        left: 0,
        right: 0,
        paddingTop: Platform.OS === 'android' ? 40 : 50,
        paddingBottom: 12,
        paddingHorizontal: 20,
        backgroundColor: 'rgba(0, 0, 0, 0.4)',
    },
    appTitle: {
        color: '#FFFFFF',
        fontSize: 18,
        fontWeight: '800',
        letterSpacing: 0.5,
    },
    // Preview screen
    previewContainer: {
        flex: 1,
        backgroundColor: '#0D0D0D',
    },
    previewImage: {
        flex: 1,
    },
    previewDepthBadge: {
        position: 'absolute',
        top: 60,
        alignSelf: 'center',
        backgroundColor: 'rgba(0, 0, 0, 0.75)',
        paddingVertical: 8,
        paddingHorizontal: 20,
        borderRadius: 20,
        borderWidth: 1,
        borderColor: '#00E676',
    },
    previewDepthText: {
        color: '#00E676',
        fontSize: 16,
        fontWeight: '700',
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
    saveText: {
        color: '#FFFFFF',
        fontSize: 16,
        fontWeight: '700',
    },
});
