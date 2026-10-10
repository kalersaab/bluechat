import type {CodegenTypes, TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type BluetoothDevice = {
  id: string;
  name: string;
  rssi: number;
  paired: boolean;
};

export type ConnectionState = {
  connected: boolean;
  deviceId: string;
};

export type ChatMessage = {
  text: string;
  deviceId: string;
};

export interface Spec extends TurboModule {
  readonly startBluetoothSearch: () => void;
  readonly stopBluetoothSearch: () => void;
  readonly isBluetoothSearching: () => boolean;

  readonly connect: (deviceId: string) => void;
  readonly disconnect: () => void;
  readonly sendMessage: (text: string) => void;

  readonly onDeviceFound: CodegenTypes.EventEmitter<BluetoothDevice>;
  readonly onSearchStateChanged: CodegenTypes.EventEmitter<boolean>;
  readonly onConnectionStateChanged: CodegenTypes.EventEmitter<ConnectionState>;
  readonly onMessageReceived: CodegenTypes.EventEmitter<ChatMessage>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('BluetoothScanner');
