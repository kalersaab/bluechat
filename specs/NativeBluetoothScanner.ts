import type {CodegenTypes, TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type BluetoothDevice = {
  id: string;
  name: string;
  rssi: number;
  paired: boolean;
};

export interface Spec extends TurboModule {
  readonly startBluetoothSearch: () => void;
  readonly stopBluetoothSearch: () => void;
  readonly isBluetoothSearching: () => boolean;

  readonly onDeviceFound: CodegenTypes.EventEmitter<BluetoothDevice>;
  readonly onSearchStateChanged: CodegenTypes.EventEmitter<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('BluetoothScanner');
