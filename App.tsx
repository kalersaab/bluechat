import { useCallback, useEffect, useRef, useState } from 'react';
import { StatusBar, TurboModuleRegistry } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import BluetoothSearchScreen from './screen/BluetoothSearchScreen';
import ChatScreen from './screen/ChatScreen';
import type {
  BluetoothDevice,
  Spec,
} from './specs/NativeBluetoothScanner';

const NativeModule = TurboModuleRegistry.get<Spec>('BluetoothScanner');

type ActiveChat = {
  device: BluetoothDevice;
  initiator: boolean;
};

const PEER_DEVICE = (deviceId: string): BluetoothDevice => ({
  id: deviceId,
  name: 'Connected device',
  rssi: 0,
  paired: false,
});

function App() {
  const [active, setActive] = useState<ActiveChat | null>(null);
  const activeRef = useRef<ActiveChat | null>(null);

  const openChat = useCallback((chat: ActiveChat) => {
    activeRef.current = chat;
    setActive(chat);
  }, []);

  const handleBack = useCallback(() => {
    activeRef.current = null;
    setActive(null);
  }, []);

  const handleSelectDevice = useCallback(
    (device: BluetoothDevice) => openChat({ device, initiator: true }),
    [openChat],
  );

  useEffect(() => {
    if (!NativeModule) {
      return;
    }
    const subscription = NativeModule.onConnectionStateChanged(state => {
      if (state.connected && activeRef.current === null) {
        openChat({ device: PEER_DEVICE(state.deviceId), initiator: false });
      }
    });
    return () => subscription.remove();
  }, [openChat]);

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      {active ? (
        <ChatScreen
          device={active.device}
          initiator={active.initiator}
          onBack={handleBack}
        />
      ) : (
        <BluetoothSearchScreen onSelectDevice={handleSelectDevice} />
      )}
    </SafeAreaProvider>
  );
}

export default App;
