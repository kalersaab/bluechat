/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */

import { StatusBar } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import BluetoothSearchScreen from './screen/BluetoothSearchScreen';

function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <BluetoothSearchScreen />
    </SafeAreaProvider>
  );
}

export default App;
