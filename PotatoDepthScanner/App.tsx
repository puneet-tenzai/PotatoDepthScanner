import React from 'react';
import { SafeAreaView, StyleSheet } from 'react-native';
import { CameraScreen } from './src/screens/CameraScreen';

const App: React.FC = () => {
  return (
    <SafeAreaView style={styles.container}>
      <CameraScreen />
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000',
  },
});

export default App;
//try