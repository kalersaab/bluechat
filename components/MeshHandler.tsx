import { useEffect } from 'react';
import { TurboModuleRegistry } from 'react-native';
import type { Spec } from '../specs/NativeBluetoothScanner';

const NativeModule = TurboModuleRegistry.get<Spec>('BluetoothScanner');

function MeshHandler() {
  useEffect(() => {
    if (!NativeModule) {
      return;
    }

    const meshSubscription = NativeModule.onMeshMessageReceived(_message => {
    });

    return () => {
      meshSubscription.remove();
    };
  }, []);

  return null;
}

export default MeshHandler;
